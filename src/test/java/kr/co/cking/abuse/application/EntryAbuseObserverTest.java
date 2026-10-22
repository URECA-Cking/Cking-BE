package kr.co.cking.abuse.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import kr.co.cking.abuse.application.classification.EntryResultClassifier;
import kr.co.cking.abuse.domain.AbuseActionType;
import kr.co.cking.abuse.domain.AbuseObservationEvent;
import kr.co.cking.abuse.domain.BalanceScope;
import kr.co.cking.abuse.domain.ResultClassification;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.event.domain.EntryErrorCode;
import kr.co.cking.event.domain.EntryResultCode;
import kr.co.cking.ticket.domain.CouponType;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class EntryAbuseObserverTest {

    private static final Instant REQUESTED_AT = Instant.parse("2026-10-06T23:59:59Z");
    private static final Instant OBSERVED_AT = Instant.parse("2026-10-07T00:00:01Z");

    private final AbuseObservationExecutor executor = mock(AbuseObservationExecutor.class);
    private final EntryAbuseObserver observer = new EntryAbuseObserver(
            new EntryResultClassifier(), executor, Clock.fixed(OBSERVED_AT, ZoneOffset.UTC));

    @Test
    void CREATOR_응모_성공은_Event_소유자_잔액_범위로_관찰한다() {
        EntryObservationContext context = context(CouponType.CREATOR);

        observer.observeSuccess(context, EntryResultCode.SUCCESS);

        AbuseObservationEvent event = captured();
        assertThat(event.actionType()).isEqualTo(AbuseActionType.EVENT_ENTRY);
        assertThat(event.userId()).isEqualTo(17L);
        assertThat(event.creatorId()).isEqualTo(5L);
        assertThat(event.eventId()).isEqualTo(103L);
        assertThat(event.requestId()).isEqualTo(context.requestId());
        assertThat(event.resultCode()).isEqualTo("SUCCESS");
        assertThat(event.resultClassification()).isEqualTo(ResultClassification.NEW_SUCCESS);
        assertThat(event.balanceScope()).isEqualTo(BalanceScope.creator(5L));
        assertThat(event.missionId()).isNull();
        assertThat(event.periodKey()).isNull();
        assertThat(event.businessKey()).isNull();
        assertThat(event.requestedAt()).isEqualTo(REQUESTED_AT);
        assertThat(event.observedAt()).isEqualTo(OBSERVED_AT);
    }

    @Test
    void COMMON_응모_재요청은_Event_소유자를_보존하되_공용_잔액으로_관찰한다() {
        observer.observeSuccess(context(CouponType.COMMON), EntryResultCode.DUPLICATE_REPLAY);

        AbuseObservationEvent event = captured();
        assertThat(event.creatorId()).isEqualTo(5L);
        assertThat(event.balanceScope()).isEqualTo(BalanceScope.common());
        assertThat(event.resultClassification()).isEqualTo(ResultClassification.REPLAY);
    }

    @Test
    void 부족_잔액과_기술_오류는_원래_결과코드로_분류한다() {
        EntryObservationContext context = context(CouponType.COMMON);

        observer.observeFailure(context, EntryErrorCode.INSUFFICIENT_BALANCE);
        observer.observeFailure(context, EntryErrorCode.BALANCE_NOT_LOADED);

        ArgumentCaptor<AbuseObservationEvent> captor = ArgumentCaptor.forClass(AbuseObservationEvent.class);
        verify(executor, times(2)).observe(captor.capture());
        assertThat(captor.getAllValues()).extracting(AbuseObservationEvent::resultCode)
                .containsExactly("INSUFFICIENT_BALANCE", "BALANCE_NOT_LOADED");
        assertThat(captor.getAllValues()).extracting(AbuseObservationEvent::resultClassification)
                .containsExactly(ResultClassification.BUSINESS_FAILURE, ResultClassification.SYSTEM_FAILURE);
    }

    @Test
    void 동일_requestId의_서로_다른_시도는_ObservationId를_새로_만든다() {
        EntryObservationContext context = context(CouponType.CREATOR);

        observer.observeSuccess(context, EntryResultCode.SUCCESS);
        observer.observeSuccess(context, EntryResultCode.DUPLICATE_REPLAY);

        ArgumentCaptor<AbuseObservationEvent> captor = ArgumentCaptor.forClass(AbuseObservationEvent.class);
        verify(executor, times(2)).observe(captor.capture());
        assertThat(captor.getAllValues()).extracting(AbuseObservationEvent::requestId)
                .containsExactly(context.requestId(), context.requestId());
        assertThat(captor.getAllValues().get(0).observationId())
                .isNotEqualTo(captor.getAllValues().get(1).observationId());
    }

    @Test
    void 관찰_범위_밖_오류는_제외하고_예상치_못한_오류는_시스템_실패로_기록한다() {
        EntryObservationContext context = context(CouponType.CREATOR);

        observer.observeFailure(context, CommonErrorCode.RESOURCE_NOT_FOUND);
        observer.observeFailure(context, EntryErrorCode.INVALID_TICKET_COUNT);
        verifyNoInteractions(executor);

        observer.observeUnexpectedFailure(context);
        AbuseObservationEvent event = captured();
        assertThat(event.resultCode()).isEqualTo("SYSTEM_ERROR");
        assertThat(event.resultClassification()).isEqualTo(ResultClassification.SYSTEM_FAILURE);
    }

    @Test
    void 관찰_실행_오류는_응모_결과에_전파하지_않는다() {
        EntryObservationContext context = context(CouponType.CREATOR);
        doThrow(new IllegalStateException("Redis failure")).when(executor).observe(any(AbuseObservationEvent.class));

        assertThatCode(() -> observer.observeSuccess(context, EntryResultCode.SUCCESS)).doesNotThrowAnyException();
        assertThatCode(() -> observer.observeFailure(context, EntryErrorCode.INSUFFICIENT_BALANCE))
                .doesNotThrowAnyException();
    }

    private EntryObservationContext context(CouponType couponType) {
        return new EntryObservationContext(17L, 5L, 103L, UUID.randomUUID(), couponType, REQUESTED_AT);
    }

    private AbuseObservationEvent captured() {
        ArgumentCaptor<AbuseObservationEvent> captor = ArgumentCaptor.forClass(AbuseObservationEvent.class);
        verify(executor).observe(captor.capture());
        return captor.getValue();
    }
}
