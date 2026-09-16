package kr.co.cking.event;

import kr.co.cking.ticket.TicketBalanceQueryService;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EventQueryServiceTest {

    @Test
    void 이벤트_목록을_displayStatus를_포함한_요약으로_변환한다() {
        Instant now = Instant.parse("2026-09-15T00:00:00Z");
        Clock clock = Clock.fixed(now, ZoneOffset.UTC);
        Event event = Event.builder()
                .creatorId(1L)
                .title("여름 이벤트")
                .startAt(Instant.parse("2026-09-01T00:00:00Z"))
                .endAt(Instant.parse("2026-09-30T00:00:00Z"))
                .winnerCount(3)
                .status(EventStatus.OPEN)
                .build();
        EventRepository eventRepository = mock(EventRepository.class);
        PageRequest pageable = PageRequest.of(0, 10);
        when(eventRepository.search(eq(1L), eq(DisplayStatus.IN_PROGRESS), eq(now), eq(pageable)))
                .thenReturn(new PageImpl<>(List.of(event)));
        EventQueryService service =
                new EventQueryService(eventRepository, clock, mock(TicketBalanceQueryService.class),
                        mock(EventCache.class));

        Page<EventSummary> result = service.getEvents(1L, DisplayStatus.IN_PROGRESS, pageable);

        assertThat(result.getContent()).hasSize(1);
        EventSummary summary = result.getContent().get(0);
        assertThat(summary.title()).isEqualTo("여름 이벤트");
        assertThat(summary.displayStatus()).isEqualTo(DisplayStatus.IN_PROGRESS);
    }
}
