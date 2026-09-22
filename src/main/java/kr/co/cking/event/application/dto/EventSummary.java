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
        String prizeAlgorithmVersion,
        List<PrizeResult> prizes
) {

    public EventSummary(Long eventId, Long creatorId, String title, Instant startAt, Instant endAt, EventStatus status,
                        DisplayStatus displayStatus, Integer winnerCount, String drawMethod) {
        this(eventId, creatorId, title, startAt, endAt, status, displayStatus, winnerCount, drawMethod,
                "PRIZE_WEIGHTED_V1", List.of());
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
                event.getPrizeAlgorithmVersion(),
                event.getPrizeConfigs().stream().map(PrizeResult::from).toList()
        );
    }

    /** 목록 캐시(CachedEventPage)에서 읽을 때 쓴다 — displayStatus는 캐시 시점이 아니라 now로 새로 계산한다. */
    public static EventSummary from(CachedEvent event, Instant now) {
        return new EventSummary(
                event.eventId(),
                event.creatorId(),
                event.title(),
                event.startAt(),
                event.endAt(),
                event.status(),
                DisplayStatus.of(event.status(), event.endAt(), now),
                event.winnerCount(),
                event.drawMethod(),
                event.prizeAlgorithmVersion(),
                event.prizes().stream().map(PrizeResult::from).toList()
        );
    }
}
