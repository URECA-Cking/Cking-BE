package kr.co.cking.event;

import java.time.Instant;

public record EventDetail(
        Long eventId,
        Long creatorId,
        String title,
        String description,
        Instant startAt,
        Instant endAt,
        Integer winnerCount,
        DisplayStatus displayStatus,
        long myTicketBalance
) {

    public static EventDetail of(CachedEvent event, Instant now, long myTicketBalance) {
        return new EventDetail(
                event.eventId(),
                event.creatorId(),
                event.title(),
                event.description(),
                event.startAt(),
                event.endAt(),
                event.winnerCount(),
                DisplayStatus.of(event.status(), event.endAt(), now),
                myTicketBalance
        );
    }
}
