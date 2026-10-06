package kr.co.cking.abuse.application;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import kr.co.cking.abuse.application.classification.MissionResultClassifier;
import kr.co.cking.abuse.application.context.MissionBusinessKey;
import kr.co.cking.abuse.application.context.MissionBusinessKeyFactory;
import kr.co.cking.abuse.domain.AbuseActionType;
import kr.co.cking.abuse.domain.AbuseObservationEvent;
import kr.co.cking.abuse.domain.BalanceScope;
import kr.co.cking.abuse.domain.ResultClassification;
import kr.co.cking.common.exception.ErrorCode;
import kr.co.cking.ticket.application.dto.EarnResultCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/** Mission 결과를 Abuse Observation으로 변환하되 변환·평가 장애를 원 업무에서 격리한다. */
@Component
@RequiredArgsConstructor
@Slf4j
public class MissionAbuseObserver {

    private final MissionResultClassifier classifier;
    private final MissionBusinessKeyFactory businessKeyFactory;
    private final AbuseObservationExecutor executor;
    private final Clock clock;

    public void observeSuccess(MissionObservationContext context, EarnResultCode code) {
        try {
            classifier.classify(code).ifPresent(result -> observe(context, code.name(), result, clock.instant()));
        } catch (RuntimeException exception) {
            logFailure(exception);
        }
    }

    public void observeFailure(MissionObservationContext context, ErrorCode code) {
        try {
            classifier.classify(code).ifPresent(result -> observe(context, code.code(), result, clock.instant()));
        } catch (RuntimeException exception) {
            logFailure(exception);
        }
    }

    public void observeUnexpectedFailure(MissionObservationContext context) {
        try {
            observe(context, "SYSTEM_ERROR", ResultClassification.SYSTEM_FAILURE, clock.instant());
        } catch (RuntimeException exception) {
            logFailure(exception);
        }
    }

    private void observe(
            MissionObservationContext context, String resultCode, ResultClassification result, Instant observedAt
    ) {
        MissionBusinessKey businessKey = context.creatorType() == null
                ? businessKeyFactory.forCommon(
                        context.commonType(), context.userId(), context.missionId(), context.requestedAt())
                : businessKeyFactory.forCreator(
                        context.creatorType(), context.userId(), context.creatorId(), context.missionId(),
                        context.requestedAt());
        BalanceScope balanceScope = context.creatorId() == null
                ? BalanceScope.common() : BalanceScope.creator(context.creatorId());
        executor.observe(new AbuseObservationEvent(
                UUID.randomUUID(), context.userId(), AbuseActionType.MISSION_COMPLETE, context.requestId(),
                resultCode, result, context.creatorId(), null, context.missionId(), businessKey.periodKey(),
                balanceScope, businessKey.value(), context.requestedAt(), observedAt));
    }

    private void logFailure(RuntimeException exception) {
        log.warn("[ABUSE_OBSERVATION_FAILED] stage=MISSION_CONTEXT failureType={}",
                exception.getClass().getName());
    }
}
