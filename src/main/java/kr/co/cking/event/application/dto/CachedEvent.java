package kr.co.cking.event.application.dto;

import java.io.Serializable;
import java.time.Instant;

import kr.co.cking.event.domain.Event;
import kr.co.cking.event.domain.EventStatus;

public record CachedEvent(
        Long eventId,
        Long creatorId,
        String title,
        String description,
        Instant startAt,
        Instant endAt,
        Integer winnerCount,
        String drawMethod,
        EventStatus status
) implements Serializable {

    public static CachedEvent from(Event event) {
        return new CachedEvent(
                event.getEventId(),
                event.getCreatorId(),
                event.getTitle(),
                event.getDescription(),
                event.getStartAt(),
                event.getEndAt(),
                event.getWinnerCount(),
                event.getDrawMethod(),
                event.getStatus()
        );
    }
}
