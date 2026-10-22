package kr.co.cking.abuse.application;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import kr.co.cking.abuse.application.classification.EntryResultClassifier;
import kr.co.cking.abuse.domain.AbuseActionType;
import kr.co.cking.abuse.domain.AbuseObservationEvent;
import kr.co.cking.abuse.domain.BalanceScope;
import kr.co.cking.abuse.domain.ResultClassification;
import kr.co.cking.common.exception.ErrorCode;
import kr.co.cking.event.domain.EntryResultCode;
import kr.co.cking.ticket.domain.CouponType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/** Event 응모 결과를 Abuse Observation으로 변환하되 탐지 장애를 원 응모에서 격리한다. */
@Component
@RequiredArgsConstructor
@Slf4j
public class EntryAbuseObserver {

    private final EntryResultClassifier classifier;
    private final AbuseObservationExecutor executor;
    private final Clock clock;

    public void observeSuccess(EntryObservationContext context, EntryResultCode code) {
        try {
            classifier.classify(code).ifPresent(result -> observe(context, code.name(), result, clock.instant()));
        } catch (RuntimeException exception) {
            logFailure(exception);
        }
    }

    public void observeFailure(EntryObservationContext context, ErrorCode code) {
        try {
            classifier.classify(code).ifPresent(result -> observe(context, code.code(), result, clock.instant()));
        } catch (RuntimeException exception) {
            logFailure(exception);
        }
    }

    public void observeUnexpectedFailure(EntryObservationContext context) {
        try {
            observe(context, "SYSTEM_ERROR", ResultClassification.SYSTEM_FAILURE, clock.instant());
        } catch (RuntimeException exception) {
            logFailure(exception);
        }
    }

    private void observe(
            EntryObservationContext context, String resultCode, ResultClassification result, Instant observedAt
    ) {
        BalanceScope balanceScope = context.couponType() == CouponType.COMMON
                ? BalanceScope.common() : BalanceScope.creator(context.creatorId());
        executor.observe(new AbuseObservationEvent(
                UUID.randomUUID(), context.userId(), AbuseActionType.EVENT_ENTRY, context.requestId(),
                resultCode, result, context.creatorId(), context.eventId(), null, null,
                balanceScope, null, context.requestedAt(), observedAt));
    }

    private void logFailure(RuntimeException exception) {
        log.warn("[ABUSE_OBSERVATION_FAILED] stage=ENTRY_CONTEXT failureType={}",
                exception.getClass().getName());
    }
}
