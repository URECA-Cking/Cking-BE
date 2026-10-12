package kr.co.cking.creator.application;

import java.time.Clock;
import java.time.LocalDateTime;
import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.creator.domain.Creator;
import kr.co.cking.creator.domain.CreatorActivityName;
import kr.co.cking.creator.domain.CreatorErrorCode;
import kr.co.cking.creator.repository.CreatorRepository;
import kr.co.cking.member.application.MemberNicknameContentPolicy;
import kr.co.cking.member.domain.MemberNickname;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Creator 활동명 조회·변경과 승인 시 최초 활동명 후보 생성을 처리한다. */
@Service
@RequiredArgsConstructor
public class CreatorActivityNameService {

    private final CreatorRepository creatorRepository;
    private final CreatorActivityNameRegistry registry;
    private final MemberNicknameContentPolicy contentPolicy;
    private final Clock clock;

    @Transactional
    public ActivityNameProfile findMine(Long memberId) {
        Creator creator = requireCreator(memberId);
        return toProfile(creator, LocalDateTime.now(clock));
    }

    @Transactional
    public ActivityNameProfile updateMine(Long memberId, String requestedName) {
        Creator creator = creatorRepository.findByMemberIdForUpdate(memberId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.FORBIDDEN));
        CreatorActivityName requested = parse(requestedName);
        if (creator.hasCurrentActivityName(requested)) {
            return toProfile(creator, LocalDateTime.now(clock));
        }
        LocalDateTime now = LocalDateTime.now(clock);
        if (!creator.isActivityNameConfigured()) {
            if (registry.isTaken(requested, creator.getCreatorId(), now)) {
                throw new BusinessException(CreatorErrorCode.ACTIVITY_NAME_ALREADY_TAKEN);
            }
            creator.setInitialActivityName(requested);
        } else {
            boolean revert = creator.getPreviousActivityNameNormalized() != null
                    && creator.getPreviousActivityNameNormalized().equals(requested.normalized())
                    && registry.findOwnedActive(requested, creator.getCreatorId(), now).isPresent();
            if (revert) {
                CreatorActivityName released = new CreatorActivityName(
                        creator.getActivityName(), creator.getActivityNameNormalized());
                creator.revertActivityName();
                registry.clear(requested);
                registry.reserveReleased(released, creator.getCreatorId(), now);
            } else {
                if (!creator.canChangeActivityNameAt(now)) {
                    throw new BusinessException(CreatorErrorCode.ACTIVITY_NAME_CHANGE_TOO_SOON);
                }
                if (registry.isTaken(requested, creator.getCreatorId(), now)) {
                    throw new BusinessException(CreatorErrorCode.ACTIVITY_NAME_ALREADY_TAKEN);
                }
                CreatorActivityName released = new CreatorActivityName(
                        creator.getActivityName(), creator.getActivityNameNormalized());
                creator.changeActivityName(requested, now);
                registry.clear(requested);
                registry.reserveReleased(released, creator.getCreatorId(), now);
            }
        }
        try {
            creatorRepository.saveAndFlush(creator);
        } catch (DataIntegrityViolationException exception) {
            throw new BusinessException(CreatorErrorCode.ACTIVITY_NAME_ALREADY_TAKEN);
        }
        return toProfile(creator, now);
    }

    /** 승인 트랜잭션에서 신규 Creator의 첫 활동명 후보를 충돌 없이 만든다. */
    @Transactional
    public CreatorActivityName initialAvailableName(String memberNickname) {
        CreatorActivityName requested = parse(memberNickname);
        LocalDateTime now = LocalDateTime.now(clock);
        if (!registry.isTaken(requested, null, now)) {
            return requested;
        }
        for (int suffix = 2; suffix <= 9999; suffix++) {
            String postfix = "-" + suffix;
            String base = requested.value();
            String candidate = base.substring(0, Math.min(base.length(), CreatorActivityName.MAX_LENGTH - postfix.length())) + postfix;
            CreatorActivityName alternative = CreatorActivityName.of(candidate);
            if (!registry.isTaken(alternative, null, now)) {
                return alternative;
            }
        }
        throw new BusinessException(CreatorErrorCode.ACTIVITY_NAME_ALREADY_TAKEN);
    }

    private Creator requireCreator(Long memberId) {
        return creatorRepository.findByMemberId(memberId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.FORBIDDEN));
    }

    private CreatorActivityName parse(String requestedName) {
        try {
            MemberNickname nickname = MemberNickname.of(requestedName);
            contentPolicy.validate(nickname);
            return new CreatorActivityName(nickname.value(), nickname.normalized());
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(CommonErrorCode.VALIDATION_FAILED);
        }
    }

    private ActivityNameProfile toProfile(Creator creator, LocalDateTime now) {
        String restorableActivityName = creator.getPreviousActivityNameNormalized() == null ? null
                : registry.findOwnedActive(new CreatorActivityName(
                                creator.getPreviousActivityName(), creator.getPreviousActivityNameNormalized()),
                        creator.getCreatorId(), now)
                .map(reservation -> reservation.getActivityName())
                .orElse(null);
        return ActivityNameProfile.from(creator, restorableActivityName);
    }

    public record ActivityNameProfile(
            Long creatorId,
            String activityName,
            boolean activityNameConfigured,
            java.time.Instant activityNameChangeableAt,
            String restorableActivityName
    ) {
        private static ActivityNameProfile from(Creator creator, String restorableActivityName) {
            LocalDateTime changeableAt = creator.activityNameChangeableAt();
            return new ActivityNameProfile(
                    creator.getCreatorId(), creator.getActivityName(), creator.isActivityNameConfigured(),
                    changeableAt == null ? null : changeableAt.toInstant(java.time.ZoneOffset.UTC),
                    restorableActivityName);
        }
    }
}
