package kr.co.cking.event.application;

import kr.co.cking.abuse.application.EntryAbuseObserver;
import kr.co.cking.abuse.application.EntryObservationContext;
import kr.co.cking.abuse.application.AbuseObservationExecutor;
import kr.co.cking.abuse.application.classification.EntryResultClassifier;
import kr.co.cking.abuse.domain.AbuseObservationEvent;
import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.event.application.dto.CachedEvent;
import kr.co.cking.event.domain.EventStatus;
import kr.co.cking.event.application.dto.EntryCommand;
import kr.co.cking.event.application.dto.EntryOutcome;
import kr.co.cking.event.application.dto.EntrySpendResult;
import kr.co.cking.event.application.dto.enums.EntrySpendResultCode;
import kr.co.cking.event.application.service.EntrySpendService;
import kr.co.cking.event.domain.EntryErrorCode;
import kr.co.cking.event.domain.EntryResultCode;
import kr.co.cking.ticket.domain.CouponType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

@ExtendWith(MockitoExtension.class)
class EventEntryServiceTest {

    @Mock
    private EventQueryService eventQueryService;

    @Mock
    private EntrySpendService entrySpendService;

    @Mock
    private EntryAbuseObserver abuseObserver;

    @Mock
    private Clock clock;

    @InjectMocks
    private EventEntryService eventEntryService;

    private final CachedEvent event = new CachedEvent(1L, 2L, "여름 이벤트", "설명",
            Instant.parse("2026-09-01T00:00:00Z"), Instant.parse("2026-09-30T00:00:00Z"),
            3, "WEIGHTED", EventStatus.OPEN);

    @Test
    void SUCCESS면_accepted_응답을_반환한다() {
        UUID requestId = UUID.randomUUID();
        EntryCommand command = new EntryCommand(10L, requestId, 2, CouponType.CREATOR);
        when(eventQueryService.getCachedEvent(1L)).thenReturn(event);
        when(clock.instant()).thenReturn(Instant.parse("2026-10-06T00:00:00Z"));
        when(entrySpendService.spend(eq(1L), eq(10L), eq(2L), eq(requestId.toString()), eq(2), eq(CouponType.CREATOR)))
                .thenReturn(EntrySpendResult.ofSuccess(EntrySpendResultCode.SUCCESS, "1-0", 1L));

        EntryOutcome outcome = eventEntryService.apply(1L, command);

        assertThat(outcome.code()).isEqualTo(EntryResultCode.SUCCESS);
        assertThat(outcome.requestId()).isEqualTo(requestId);
        assertThat(outcome.eventId()).isEqualTo(1L);
        verify(abuseObserver).observeSuccess(
                new EntryObservationContext(10L, 2L, 1L, requestId, CouponType.CREATOR,
                        Instant.parse("2026-10-06T00:00:00Z")), EntryResultCode.SUCCESS);
    }

    @Test
    void DUPLICATE_REPLAY도_accepted_응답을_반환한다() {
        EntryCommand command = new EntryCommand(10L, UUID.randomUUID(), 2, CouponType.CREATOR);
        when(eventQueryService.getCachedEvent(1L)).thenReturn(event);
        when(clock.instant()).thenReturn(Instant.parse("2026-10-06T00:00:00Z"));
        when(entrySpendService.spend(any(), any(), any(), any(), anyInt(), any()))
                .thenReturn(EntrySpendResult.ofSuccess(EntrySpendResultCode.DUPLICATE_REPLAY, "1-0", 1L));

        EntryOutcome outcome = eventEntryService.apply(1L, command);

        assertThat(outcome.code()).isEqualTo(EntryResultCode.DUPLICATE_REPLAY);
        verify(abuseObserver).observeSuccess(
                new EntryObservationContext(10L, 2L, 1L, command.requestId(), CouponType.CREATOR,
                        Instant.parse("2026-10-06T00:00:00Z")), EntryResultCode.DUPLICATE_REPLAY);
    }

    @Test
    void 실패_코드는_BusinessException으로_변환된다() {
        EntryCommand command = new EntryCommand(10L, UUID.randomUUID(), 2, CouponType.CREATOR);
        when(eventQueryService.getCachedEvent(1L)).thenReturn(event);
        when(clock.instant()).thenReturn(Instant.parse("2026-10-06T00:00:00Z"));
        when(entrySpendService.spend(any(), any(), any(), any(), anyInt(), any()))
                .thenReturn(EntrySpendResult.ofBalance(EntrySpendResultCode.INSUFFICIENT_BALANCE, 0L));

        assertThatThrownBy(() -> eventEntryService.apply(1L, command))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(EntryErrorCode.INSUFFICIENT_BALANCE);
        verify(abuseObserver).observeFailure(
                new EntryObservationContext(10L, 2L, 1L, command.requestId(), CouponType.CREATOR,
                        Instant.parse("2026-10-06T00:00:00Z")), EntryErrorCode.INSUFFICIENT_BALANCE);
    }

    @Test
    void 이벤트_조회_실패는_관찰하지_않는다() {
        EntryCommand command = new EntryCommand(10L, UUID.randomUUID(), 2, CouponType.CREATOR);
        BusinessException failure = new BusinessException(kr.co.cking.event.domain.EventErrorCode.EVENT_NOT_FOUND);
        when(eventQueryService.getCachedEvent(1L)).thenThrow(failure);

        assertThatThrownBy(() -> eventEntryService.apply(1L, command)).isSameAs(failure);
        verifyNoInteractions(abuseObserver, entrySpendService);
    }

    @Test
    void SPEND_예상치_못한_오류는_원_예외를_유지하고_시스템_실패로_관찰한다() {
        EntryCommand command = new EntryCommand(10L, UUID.randomUUID(), 2, CouponType.COMMON);
        when(eventQueryService.getCachedEvent(1L)).thenReturn(event);
        when(clock.instant()).thenReturn(Instant.parse("2026-10-06T00:00:00Z"));
        IllegalStateException failure = new IllegalStateException("Redis connection lost");
        when(entrySpendService.spend(eq(1L), eq(10L), eq(2L), eq(command.requestId().toString()),
                eq(2), eq(CouponType.COMMON))).thenThrow(failure);

        assertThatThrownBy(() -> eventEntryService.apply(1L, command)).isSameAs(failure);
        verify(abuseObserver).observeUnexpectedFailure(
                new EntryObservationContext(10L, 2L, 1L, command.requestId(), CouponType.COMMON,
                        Instant.parse("2026-10-06T00:00:00Z")));
    }

    @Test
    void 실제_Observer_실행_오류도_응모_성공_결과를_바꾸지_않는다() {
        Clock fixedClock = Clock.fixed(Instant.parse("2026-10-06T00:00:00Z"), ZoneOffset.UTC);
        AbuseObservationExecutor failedExecutor = mock(AbuseObservationExecutor.class);
        doThrow(new IllegalStateException("Redis failure"))
                .when(failedExecutor).observe(any(AbuseObservationEvent.class));
        EventEntryService service = new EventEntryService(eventQueryService, entrySpendService,
                new EntryAbuseObserver(new EntryResultClassifier(), failedExecutor, fixedClock), fixedClock);
        EntryCommand command = new EntryCommand(10L, UUID.randomUUID(), 2, CouponType.COMMON);
        when(eventQueryService.getCachedEvent(1L)).thenReturn(event);
        when(entrySpendService.spend(any(), any(), any(), any(), anyInt(), any()))
                .thenReturn(EntrySpendResult.ofSuccess(EntrySpendResultCode.SUCCESS, "1-0", 1L));

        assertThat(service.apply(1L, command).code()).isEqualTo(EntryResultCode.SUCCESS);
    }

    @Test
    void 실제_Observer_실행_오류도_응모_업무_예외를_바꾸지_않는다() {
        Clock fixedClock = Clock.fixed(Instant.parse("2026-10-06T00:00:00Z"), ZoneOffset.UTC);
        AbuseObservationExecutor failedExecutor = mock(AbuseObservationExecutor.class);
        doThrow(new IllegalStateException("Redis failure"))
                .when(failedExecutor).observe(any(AbuseObservationEvent.class));
        EventEntryService service = new EventEntryService(eventQueryService, entrySpendService,
                new EntryAbuseObserver(new EntryResultClassifier(), failedExecutor, fixedClock), fixedClock);
        EntryCommand command = new EntryCommand(10L, UUID.randomUUID(), 2, CouponType.CREATOR);
        when(eventQueryService.getCachedEvent(1L)).thenReturn(event);
        when(entrySpendService.spend(any(), any(), any(), any(), anyInt(), any()))
                .thenReturn(EntrySpendResult.ofBalance(EntrySpendResultCode.INSUFFICIENT_BALANCE, 0L));

        assertThatThrownBy(() -> service.apply(1L, command))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(EntryErrorCode.INSUFFICIENT_BALANCE);
    }
}
