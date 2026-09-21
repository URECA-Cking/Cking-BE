package kr.co.cking.event.application.dto;

import java.time.Instant;
import java.util.List;

import kr.co.cking.event.domain.DisplayStatus;
import kr.co.cking.event.domain.Event;
import kr.co.cking.event.domain.EventStatus;

public record EventSummary(
        Long eventId,
        Long creatorId,
        String title,
        Instant startAt,
        Instant endAt,
        EventStatus status,
        DisplayStatus displayStatus,
        Integer winnerCount,
        String drawMethod,
        List<PrizeResult> prizes
) {

    public EventSummary(Long eventId, Long creatorId, String title, Instant startAt, Instant endAt, EventStatus status,
                        DisplayStatus displayStatus, Integer winnerCount, String drawMethod) {
        this(eventId, creatorId, title, startAt, endAt, status, displayStatus, winnerCount, drawMethod, List.of());
    }

    public EventSummary {
        prizes = List.copyOf(prizes);
    }

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
                event.getDrawMethod(),
                event.getPrizeConfigs().stream().map(PrizeResult::from).toList()
        );
    }
}
