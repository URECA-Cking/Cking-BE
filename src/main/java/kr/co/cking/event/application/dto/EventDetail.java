package kr.co.cking.event.application.dto;

import java.time.Instant;
import java.util.List;

import kr.co.cking.event.domain.DisplayStatus;
import kr.co.cking.event.domain.EventStatus;

public record EventDetail(
        Long eventId,
        Long creatorId,
        String title,
        String description,
        Instant startAt,
        Instant endAt,
        EventStatus status,
        DisplayStatus displayStatus,
        Integer winnerCount,
        String drawMethod,
        long myTicketBalance,
        List<PrizeResult> prizes
) {

    public EventDetail(Long eventId, Long creatorId, String title, String description, Instant startAt, Instant endAt,
                       EventStatus status, DisplayStatus displayStatus, Integer winnerCount, String drawMethod,
                       long myTicketBalance) {
        this(eventId, creatorId, title, description, startAt, endAt, status, displayStatus, winnerCount, drawMethod,
                myTicketBalance, List.of());
    }

    public EventDetail {
        prizes = List.copyOf(prizes);
    }

    public static EventDetail of(CachedEvent event, Instant now, long myTicketBalance) {
        return new EventDetail(
                event.eventId(),
                event.creatorId(),
                event.title(),
                event.description(),
                event.startAt(),
                event.endAt(),
                event.status(),
                DisplayStatus.of(event.status(), event.endAt(), now),
                event.winnerCount(),
                event.drawMethod(),
                myTicketBalance,
                event.prizes().stream().map(PrizeResult::from).toList()
        );
    }
}
