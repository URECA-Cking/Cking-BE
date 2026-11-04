package kr.co.cking.subscriptionverification.application;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.creator.domain.Creator;
import kr.co.cking.creator.repository.CreatorRepository;
import kr.co.cking.member.repository.MemberRepository;
import kr.co.cking.mission.application.YoutubeSubscriptionMissionProvisioningService;
import kr.co.cking.subscriptionverification.domain.CreatorYoutubeChannel;
import kr.co.cking.subscriptionverification.domain.SubscriptionVerificationErrorCode;
import kr.co.cking.subscriptionverification.domain.SubscriptionVerificationStatus;
import kr.co.cking.subscriptionverification.repository.CreatorYoutubeChannelRepository;
import kr.co.cking.subscriptionverification.repository.SubscriptionVerificationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;

import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;

/** Creator YouTube 채널 설정·조회와 최초 구독 미션 생성을 관리한다. */
@Service
@RequiredArgsConstructor
public class CreatorYoutubeChannelService {

    private static final Set<SubscriptionVerificationStatus> ACTIVE_STATUSES =
            EnumSet.of(SubscriptionVerificationStatus.PENDING, SubscriptionVerificationStatus.PROCESSING);

    private final MemberRepository memberRepository;
    private final CreatorRepository creatorRepository;
    private final CreatorYoutubeChannelRepository channelRepository;
    private final SubscriptionVerificationRepository verificationRepository;
    private final YoutubeSubscriptionMissionProvisioningService missionProvisioningService;
    private final Clock clock;

    @Transactional(readOnly = true)
    public CreatorYoutubeChannel getPublic(Long creatorId) {
        if (!creatorRepository.existsById(creatorId)) {
            throw new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND);
        }
        CreatorYoutubeChannel channel = requireChannel(creatorId);
        if (!channel.isVerified()) {
            throw new BusinessException(SubscriptionVerificationErrorCode.CHANNEL_NOT_VERIFIED);
        }
        return channel;
    }

    @Transactional(readOnly = true)
    public CreatorYoutubeChannel getMine(Long memberId) {
        Creator creator = requireCreator(memberId);
        return requireChannel(creator.getCreatorId());
    }

    @Transactional(readOnly = true)
    public CreatorYoutubeChannel getMineOrNull(Long memberId) {
        Creator creator = requireCreator(memberId);
        return channelRepository.findById(creator.getCreatorId()).orElse(null);
    }

    @Transactional(readOnly = true)
    public Long creatorIdForConnection(Long memberId) {
        return requireCreator(memberId).getCreatorId();
    }

    /** Creator 행을 잠그고 채널 설정·미션 provisioning을 하나의 Transaction으로 처리한다. */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public CreatorYoutubeChannelUpsertResult put(Long memberId, CreatorYoutubeChannelCommand command) {
        Creator creator = requireCreator(memberId);
        Long creatorId = creator.getCreatorId();
        creatorRepository.findByIdForUpdate(creatorId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));

        String channelName = normalizeName(command);
        String channelHandle = normalizeHandle(command);
        // Creator row is the shared lock for channel writes and verification submissions.
        // Avoid SELECT FOR UPDATE on an absent channel row: its gap lock can deadlock cross-Creator inserts.
        CreatorYoutubeChannel existing = channelRepository.findById(creatorId).orElse(null);

        if (existing != null && existing.hasSameConfiguration(channelName, channelHandle)) {
            return new CreatorYoutubeChannelUpsertResult(existing, false);
        }
        if (existing != null && existing.isVerified()) {
            throw new BusinessException(SubscriptionVerificationErrorCode.CHANNEL_CONNECTION_REQUIRED);
        }
        if (existing != null && verificationRepository.existsByCreatorIdAndStatusIn(creatorId, ACTIVE_STATUSES)) {
            throw new BusinessException(SubscriptionVerificationErrorCode.INVALID_STATE);
        }
        Instant now = Instant.now(clock);
        if (existing != null) {
            existing.update(channelName, channelHandle, now);
            channelRepository.saveAndFlush(existing);
            return new CreatorYoutubeChannelUpsertResult(existing, false);
        }

        CreatorYoutubeChannel created = new CreatorYoutubeChannel(creatorId, channelName, channelHandle, now);
        channelRepository.saveAndFlush(created);
        missionProvisioningService.provision(creatorId);
        return new CreatorYoutubeChannelUpsertResult(created, true);
    }

    /** The OAuth request is checked outside this transaction; the Creator lock serializes channel changes and submissions. */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public CreatorYoutubeChannel connectVerified(Long memberId, Long expectedCreatorId,
            String channelId, String channelName,
            String channelHandle) {
        Creator creator = requireCreator(memberId);
        Long creatorId = creator.getCreatorId();
        if (!creatorId.equals(expectedCreatorId)) {
            throw new BusinessException(SubscriptionVerificationErrorCode.CHANNEL_CONNECTION_INVALID);
        }
        creatorRepository.findByIdForUpdate(creatorId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));
        CreatorYoutubeChannel existing = channelRepository.findById(creatorId).orElse(null);
        if (existing != null && verificationRepository.existsByCreatorIdAndStatusIn(creatorId, ACTIVE_STATUSES)
                && (!channelId.equals(existing.getYoutubeChannelId())
                        || !existing.hasSameConfiguration(channelName, channelHandle))) {
            throw new BusinessException(SubscriptionVerificationErrorCode.INVALID_STATE);
        }
        Instant now = Instant.now(clock);
        CreatorYoutubeChannel channel = existing == null
                ? new CreatorYoutubeChannel(creatorId, channelName, channelHandle, now) : existing;
        channel.verify(channelId, channelName, channelHandle, now);
        try {
            channelRepository.saveAndFlush(channel);
        } catch (DataIntegrityViolationException exception) {
            throw new BusinessException(SubscriptionVerificationErrorCode.CHANNEL_ALREADY_CONNECTED);
        }
        missionProvisioningService.provision(creatorId);
        return channel;
    }

    private Creator requireCreator(Long memberId) {
        memberRepository.findById(memberId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));
        return creatorRepository.findByMemberId(memberId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.FORBIDDEN));
    }

    private CreatorYoutubeChannel requireChannel(Long creatorId) {
        return channelRepository.findById(creatorId)
                .orElseThrow(() -> new BusinessException(
                        SubscriptionVerificationErrorCode.CREATOR_CHANNEL_NOT_CONFIGURED));
    }

    private String normalizeName(CreatorYoutubeChannelCommand command) {
        if (command == null) {
            throw new BusinessException(CommonErrorCode.VALIDATION_FAILED);
        }
        try {
            return CreatorYoutubeChannel.normalizeName(command.channelName());
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(CommonErrorCode.VALIDATION_FAILED);
        }
    }

    private String normalizeHandle(CreatorYoutubeChannelCommand command) {
        try {
            return CreatorYoutubeChannel.normalizeHandle(command.channelHandle());
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(CommonErrorCode.VALIDATION_FAILED);
        }
    }

}
