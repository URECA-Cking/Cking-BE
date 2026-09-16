package kr.co.cking.event;

import java.time.Instant;

public record EventSummary(
        Long eventId,
        Long creatorId,
        String title,
        Instant startAt,
        Instant endAt,
        Integer winnerCount,
        DisplayStatus displayStatus
) {

    public static EventSummary from(Event event, Instant now) {
        return new EventSummary(
                event.getEventId(),
                event.getCreatorId(),
                event.getTitle(),
                event.getStartAt(),
                event.getEndAt(),
                event.getWinnerCount(),
                event.displayStatus(now)
        );
    }
}
