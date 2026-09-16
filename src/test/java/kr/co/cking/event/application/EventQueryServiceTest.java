package kr.co.cking.event.application;

import kr.co.cking.event.application.dto.EventSummary;
import kr.co.cking.event.domain.DisplayStatus;
import kr.co.cking.event.domain.Event;
import kr.co.cking.event.domain.EventStatus;
import kr.co.cking.event.repository.EventRepository;
import kr.co.cking.member.repository.MemberRepository;
import kr.co.cking.ticket.TicketBalanceQueryService;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EventQueryServiceTest {

    private static final Sort EVENT_LIST_SORT = Sort.by(Sort.Direction.DESC, "createdAt", "eventId");

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
                .drawMethod("WEIGHTED")
                .status(EventStatus.OPEN)
                .build();
        EventRepository eventRepository = mock(EventRepository.class);
        PageRequest expectedPageable = PageRequest.of(0, 10, EVENT_LIST_SORT);
        when(eventRepository.search(eq(1L), eq(DisplayStatus.IN_PROGRESS), eq(now), eq(expectedPageable)))
                .thenReturn(new PageImpl<>(List.of(event)));
        EventQueryService service =
                new EventQueryService(eventRepository, clock, mock(TicketBalanceQueryService.class),
                        mock(EventCache.class), mock(MemberRepository.class));

        Page<EventSummary> result = service.getEvents(1L, DisplayStatus.IN_PROGRESS, 0, 10);

        assertThat(result.getContent()).hasSize(1);
        EventSummary summary = result.getContent().get(0);
        assertThat(summary.title()).isEqualTo("여름 이벤트");
        assertThat(summary.status()).isEqualTo(EventStatus.OPEN);
        assertThat(summary.drawMethod()).isEqualTo("WEIGHTED");
        assertThat(summary.displayStatus()).isEqualTo(DisplayStatus.IN_PROGRESS);
    }

    @Test
    void page_size는_요청받은_그대로_전달한다() {
        Instant now = Instant.parse("2026-09-15T00:00:00Z");
        Clock clock = Clock.fixed(now, ZoneOffset.UTC);
        EventRepository eventRepository = mock(EventRepository.class);
        when(eventRepository.search(any(), nullable(DisplayStatus.class), any(), any())).thenReturn(new PageImpl<>(List.of()));
        EventQueryService service =
                new EventQueryService(eventRepository, clock, mock(TicketBalanceQueryService.class),
                        mock(EventCache.class), mock(MemberRepository.class));

        service.getEvents(null, null, 2, 50);

        verify(eventRepository).search(eq((Long) null), eq((DisplayStatus) null), eq(now),
                eq(PageRequest.of(2, 50, EVENT_LIST_SORT)));
    }
}
