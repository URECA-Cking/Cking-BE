package kr.co.cking.event;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.ticket.TicketBalanceQueryService;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EventQueryServiceGetEventTest {

    private final Instant now = Instant.parse("2026-09-15T00:00:00Z");
    private final Clock clock = Clock.fixed(now, ZoneOffset.UTC);
    private final EventRepository eventRepository = mock(EventRepository.class);
    private final TicketBalanceQueryService ticketBalanceQueryService = mock(TicketBalanceQueryService.class);
    private final EventCache eventCache = mock(EventCache.class);
    private final EventQueryService service =
            new EventQueryService(eventRepository, clock, ticketBalanceQueryService, eventCache);

    private final Event event = Event.builder()
            .creatorId(7L)
            .title("여름 이벤트")
            .startAt(Instant.parse("2026-09-01T00:00:00Z"))
            .endAt(Instant.parse("2026-09-30T00:00:00Z"))
            .winnerCount(3)
            .status(EventStatus.OPEN)
            .build();

    @Test
    void 이벤트_상세에_내_잔액을_포함한다() {
        when(eventCache.find(1L)).thenReturn(Optional.empty());
        when(eventRepository.findById(1L)).thenReturn(Optional.of(event));
        when(ticketBalanceQueryService.getBalance(7L, 100L)).thenReturn(42L);

        EventDetail detail = service.getEvent(1L, 100L);

        assertThat(detail.myTicketBalance()).isEqualTo(42L);
        assertThat(detail.displayStatus()).isEqualTo(DisplayStatus.IN_PROGRESS);
    }

    @Test
    void 없는_이벤트를_조회하면_예외가_발생한다() {
        when(eventCache.find(1L)).thenReturn(Optional.empty());
        when(eventRepository.findById(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getEvent(1L, 100L))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void 승인전_이벤트를_조회하면_예외가_발생한다() {
        Event draft = Event.builder()
                .creatorId(7L)
                .title("초안 이벤트")
                .startAt(Instant.parse("2026-09-01T00:00:00Z"))
                .endAt(Instant.parse("2026-09-30T00:00:00Z"))
                .winnerCount(3)
                .status(EventStatus.DRAFT)
                .build();
        when(eventCache.find(1L)).thenReturn(Optional.empty());
        when(eventRepository.findById(1L)).thenReturn(Optional.of(draft));

        assertThatThrownBy(() -> service.getEvent(1L, 100L))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void 캐시에_있으면_레포지토리를_거치지_않는다() {
        CachedEvent cached = CachedEvent.from(event);
        when(eventCache.find(1L)).thenReturn(Optional.of(cached));
        when(ticketBalanceQueryService.getBalance(7L, 100L)).thenReturn(5L);

        EventDetail detail = service.getEvent(1L, 100L);

        assertThat(detail.myTicketBalance()).isEqualTo(5L);
        assertThat(detail.title()).isEqualTo("여름 이벤트");
        verify(eventRepository, never()).findById(any());
    }

    @Test
    void 캐시에_없으면_조회후_캐시에_적재한다() {
        when(eventCache.find(1L)).thenReturn(Optional.empty());
        when(eventRepository.findById(1L)).thenReturn(Optional.of(event));

        service.getEvent(1L, 100L);

        verify(eventCache).save(CachedEvent.from(event));
    }
}
