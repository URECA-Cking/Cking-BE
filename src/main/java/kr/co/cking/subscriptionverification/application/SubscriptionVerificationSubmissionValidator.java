package kr.co.cking.subscriptionverification.application;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.creator.repository.CreatorRepository;
import kr.co.cking.member.repository.MemberRepository;
import kr.co.cking.mission.Mission;
import kr.co.cking.mission.MissionRepository;
import kr.co.cking.mission.domain.MissionErrorCode;
import kr.co.cking.mission.domain.MissionType;
import kr.co.cking.subscriptionverification.domain.CreatorYoutubeChannel;
import kr.co.cking.subscriptionverification.domain.SubscriptionVerification;
import kr.co.cking.subscriptionverification.domain.SubscriptionVerificationErrorCode;
import kr.co.cking.subscriptionverification.domain.SubscriptionVerificationStatus;
import kr.co.cking.subscriptionverification.repository.CreatorYoutubeChannelRepository;
import kr.co.cking.subscriptionverification.repository.SubscriptionVerificationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 제출 전 사전 검사와 Creator 잠금 후 최종 검사가 같은 업무 규칙을 사용하도록 모은다. */
@Component
@RequiredArgsConstructor
class SubscriptionVerificationSubmissionValidator {

    private static final Duration SUBMISSION_COOLDOWN = Duration.ofSeconds(30);
    private static final long DAILY_SUBMISSION_LIMIT = 5L;
    private static final Set<SubscriptionVerificationStatus> ACTIVE_STATUSES = EnumSet.of(
            SubscriptionVerificationStatus.PENDING,
            SubscriptionVerificationStatus.PROCESSING
    );

    private final MemberRepository memberRepository;
    private final CreatorRepository creatorRepository;
    private final MissionRepository missionRepository;
    private final CreatorYoutubeChannelRepository channelRepository;
    private final SubscriptionVerificationRepository verificationRepository;

    SubscriptionVerificationSubmissionSource validateSource(
            Long memberId,
            Long creatorId,
            Long missionId,
            Instant now
    ) {
        requireMember(memberId);
        if (!creatorRepository.existsById(creatorId)) {
            throw new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND);
        }
        return validateMissionAndChannel(creatorId, missionId, now);
    }

    SubscriptionVerificationSubmissionSource validateSourceForUpdate(
            Long memberId,
            Long creatorId,
            Long missionId,
            Instant now
    ) {
        requireMember(memberId);
        creatorRepository.findByIdForUpdate(creatorId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));
        return validateMissionAndChannel(creatorId, missionId, now);
    }

    Optional<SubscriptionVerification> validateRequest(
            Long memberId,
            Long creatorId,
            Long missionId,
            String requestId,
            String requestFingerprint,
            Instant now
    ) {
        Optional<SubscriptionVerification> existing = verificationRepository.findByRequestId(requestId);
        if (existing.isPresent()) {
            if (!existing.get().getRequestFingerprint().equals(requestFingerprint)) {
                throw new BusinessException(SubscriptionVerificationErrorCode.IDEMPOTENCY_CONFLICT);
            }
            return existing;
        }

        if (verificationRepository.findByMemberIdAndCreatorIdAndMissionIdAndStatus(
                memberId, creatorId, missionId, SubscriptionVerificationStatus.APPROVED).isPresent()) {
            throw new BusinessException(SubscriptionVerificationErrorCode.VERIFICATION_ALREADY_APPROVED);
        }
        Optional<SubscriptionVerification> latest = verificationRepository
                .findFirstByMemberIdAndCreatorIdAndMissionIdOrderByCreatedAtDescVerificationIdDesc(
                        memberId, creatorId, missionId);
        // 진행 여부와 cooldown을 같은 조회 결과로 판정해 동시 Commit 사이의 불일치 창을 없앤다.
        if (latest.isPresent() && ACTIVE_STATUSES.contains(latest.get().getStatus())) {
            throw new BusinessException(SubscriptionVerificationErrorCode.VERIFICATION_IN_PROGRESS);
        }
        if (latest.isPresent() && latest.get().getCreatedAt().isAfter(now.minus(SUBMISSION_COOLDOWN))) {
            throw new BusinessException(
                    SubscriptionVerificationErrorCode.VERIFICATION_SUBMISSION_LIMIT_EXCEEDED);
        }

        LocalDate utcDate = now.atZone(ZoneOffset.UTC).toLocalDate();
        Instant dayStart = utcDate.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant nextDayStart = utcDate.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        long dailyCount = verificationRepository
                .countByMemberIdAndCreatorIdAndMissionIdAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(
                        memberId, creatorId, missionId, dayStart, nextDayStart);
        if (dailyCount >= DAILY_SUBMISSION_LIMIT) {
            throw new BusinessException(
                    SubscriptionVerificationErrorCode.VERIFICATION_SUBMISSION_LIMIT_EXCEEDED);
        }
        return Optional.empty();
    }

    private SubscriptionVerificationSubmissionSource validateMissionAndChannel(
            Long creatorId,
            Long missionId,
            Instant now
    ) {
        Mission mission = missionRepository.findById(missionId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));
        if (!creatorId.equals(mission.getCreatorId()) || mission.getType() != MissionType.YOUTUBE_SUBSCRIPTION) {
            throw new BusinessException(SubscriptionVerificationErrorCode.INVALID_VERIFICATION_MISSION);
        }
        if (!mission.isActiveAt(now)) {
            throw new BusinessException(MissionErrorCode.MISSION_INACTIVE);
        }
        CreatorYoutubeChannel channel = channelRepository.findById(creatorId)
                .orElseThrow(() -> new BusinessException(
                        SubscriptionVerificationErrorCode.CREATOR_CHANNEL_NOT_CONFIGURED));
        return new SubscriptionVerificationSubmissionSource(mission, channel);
    }

    private void requireMember(Long memberId) {
        if (!memberRepository.existsById(memberId)) {
            throw new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND);
        }
    }
}
