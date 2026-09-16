package kr.co.cking.event;

import java.time.Instant;

public record EventSummary(
        Long eventId,
        Long creatorId,
        String title,
        Instant startAt,
        Instant endAt,
        EventStatus status,
        DisplayStatus displayStatus,
        Integer winnerCount,
        String drawMethod
) {

    public static EventSummary from(Event event, Instant now) {
        return new EventSummary(
                event.getEventId(),
                event.getCreatorId(),
                event.getTitle(),
                event.getStartAt(),
                event.getEndAt(),
                event.getStatus(),
                event.displayStatus(now),
                event.getWinnerCount(),
                event.getDrawMethod()
        );
    }
}
