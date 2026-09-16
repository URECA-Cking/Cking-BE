package kr.co.cking.event.application;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.event.application.dto.CachedEvent;
import kr.co.cking.event.domain.EventStatus;
import kr.co.cking.event.application.dto.EntryOutcome;
import kr.co.cking.event.application.dto.EntrySpendResult;
import kr.co.cking.event.application.dto.enums.EntrySpendResultCode;
import kr.co.cking.event.application.service.EntrySpendService;
import kr.co.cking.event.domain.EntryErrorCode;
import kr.co.cking.event.domain.EntryResultCode;
import kr.co.cking.event.presentation.dto.EntryRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EventEntryServiceTest {

    @Mock
    private EventQueryService eventQueryService;

    @Mock
    private EntrySpendService entrySpendService;

    @InjectMocks
    private EventEntryService eventEntryService;

    private final CachedEvent event = new CachedEvent(1L, 2L, "여름 이벤트", "설명",
            Instant.parse("2026-09-01T00:00:00Z"), Instant.parse("2026-09-30T00:00:00Z"),
            3, "WEIGHTED", EventStatus.OPEN);

    @Test
    void SUCCESS면_accepted_응답을_반환한다() {
        UUID requestId = UUID.randomUUID();
        EntryRequest request = new EntryRequest(10L, requestId, 2);
        when(eventQueryService.getCachedEvent(1L)).thenReturn(event);
        when(entrySpendService.spend(eq(1L), eq(10L), eq(2L), eq(requestId.toString()), eq(2)))
                .thenReturn(EntrySpendResult.ofSuccess(EntrySpendResultCode.SUCCESS, "1-0", 1L));

        EntryOutcome outcome = eventEntryService.apply(1L, request);

        assertThat(outcome.code()).isEqualTo(EntryResultCode.SUCCESS);
        assertThat(outcome.response().accepted()).isTrue();
        assertThat(outcome.response().requestId()).isEqualTo(requestId);
    }

    @Test
    void DUPLICATE_REPLAY도_accepted_응답을_반환한다() {
        EntryRequest request = new EntryRequest(10L, UUID.randomUUID(), 2);
        when(eventQueryService.getCachedEvent(1L)).thenReturn(event);
        when(entrySpendService.spend(any(), any(), any(), any(), anyInt()))
                .thenReturn(EntrySpendResult.ofSuccess(EntrySpendResultCode.DUPLICATE_REPLAY, "1-0", 1L));

        EntryOutcome outcome = eventEntryService.apply(1L, request);

        assertThat(outcome.code()).isEqualTo(EntryResultCode.DUPLICATE_REPLAY);
        assertThat(outcome.response().accepted()).isTrue();
    }

    @Test
    void 실패_코드는_BusinessException으로_변환된다() {
        EntryRequest request = new EntryRequest(10L, UUID.randomUUID(), 2);
        when(eventQueryService.getCachedEvent(1L)).thenReturn(event);
        when(entrySpendService.spend(any(), any(), any(), any(), anyInt()))
                .thenReturn(EntrySpendResult.ofBalance(EntrySpendResultCode.INSUFFICIENT_BALANCE, 0L));

        assertThatThrownBy(() -> eventEntryService.apply(1L, request))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(EntryErrorCode.INSUFFICIENT_BALANCE);
    }
}
