package kr.co.cking.abuse.application;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import kr.co.cking.abuse.application.context.MissionBusinessKey;
import kr.co.cking.abuse.application.context.MissionBusinessKeyFactory;
import kr.co.cking.abuse.domain.AbuseActionType;
import kr.co.cking.abuse.domain.AbuseObservationEvent;
import kr.co.cking.abuse.domain.BalanceScope;
import kr.co.cking.abuse.domain.ResultClassification;
import kr.co.cking.subscriptionverification.application.vision.SubscriptionVerificationDecisionStatus;
import kr.co.cking.subscriptionverification.application.SubscriptionVerificationProcessingClaim;
import kr.co.cking.subscriptionverification.domain.SubscriptionVerification;
import kr.co.cking.subscriptionverification.domain.SubscriptionVerificationImageReuseType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/** 구독 인증 업무 결과를 비차단 Abuse Observation으로 바꾼다. 이미지 원문은 전달하지 않는다. */
@Component
@RequiredArgsConstructor
@Slf4j
public class SubscriptionVerificationAbuseObserver {

    private final MissionBusinessKeyFactory businessKeyFactory;
    private final AbuseObservationExecutor executor;
    private final Clock clock;

    public void observeCreated(SubscriptionVerification verification) {
        observe(verification, requestIdOf(verification), "SUBMISSION_CREATED", ResultClassification.NEW_SUCCESS,
                verification.getCreatedAt());
    }

    public void observeReplay(SubscriptionVerification verification) {
        observe(verification, requestIdOf(verification), "SUBMISSION_REPLAY", ResultClassification.REPLAY,
                verification.getCreatedAt());
    }

    public void observeBusinessFailure(Long memberId, Long creatorId, Long missionId, UUID requestId, String code, Instant requestedAt) {
        observe(memberId, creatorId, missionId, requestId, code, ResultClassification.BUSINESS_FAILURE, requestedAt);
    }

    public void observeSystemFailure(Long memberId, Long creatorId, Long missionId, UUID requestId, Instant requestedAt) {
        observe(memberId, creatorId, missionId, requestId, "SYSTEM_ERROR", ResultClassification.SYSTEM_FAILURE, requestedAt);
    }

    public void observeImageReuse(SubscriptionVerification verification, SubscriptionVerificationImageReuseType reuseType) {
        if (reuseType == SubscriptionVerificationImageReuseType.DIFFERENT_MEMBER) {
            observe(verification, requestIdOf(verification), "IMAGE_REUSE", ResultClassification.BUSINESS_FAILURE,
                    verification.getCreatedAt());
        }
    }

    public void observeDecision(SubscriptionVerificationProcessingClaim claim, SubscriptionVerificationDecisionStatus status) {
        ResultClassification classification = status == SubscriptionVerificationDecisionStatus.APPROVED
                ? ResultClassification.NEW_SUCCESS : ResultClassification.BUSINESS_FAILURE;
        observe(claim.memberId(), claim.creatorId(), claim.missionId(),
                UUID.nameUUIDFromBytes(("verification:" + claim.verificationId()).getBytes()), status.name(),
                classification, clock.instant());
    }

    private void observe(SubscriptionVerification verification, UUID requestId, String code,
                         ResultClassification classification, Instant requestedAt) {
        observe(verification.getMemberId(), verification.getCreatorId(), verification.getMissionId(), requestId,
                code, classification, requestedAt);
    }

    private void observe(Long memberId, Long creatorId, Long missionId, UUID requestId, String code,
                         ResultClassification classification, Instant requestedAt) {
        try {
            MissionBusinessKey businessKey = businessKeyFactory.forCreator(
                    kr.co.cking.mission.domain.MissionType.YOUTUBE_SUBSCRIPTION,
                    memberId, creatorId, missionId, requestedAt);
            executor.observe(new AbuseObservationEvent(UUID.randomUUID(), memberId,
                    AbuseActionType.YOUTUBE_SUBSCRIPTION, requestId, code, classification, creatorId, null,
                    missionId, null, BalanceScope.creator(creatorId), businessKey.value(), requestedAt, clock.instant()));
        } catch (RuntimeException exception) {
            log.warn("[ABUSE_OBSERVATION_FAILED] stage=YOUTUBE_SUBSCRIPTION failureType={}",
                    exception.getClass().getName());
        }
    }

    private UUID requestIdOf(SubscriptionVerification verification) {
        return UUID.fromString(verification.getRequestId());
    }
}
