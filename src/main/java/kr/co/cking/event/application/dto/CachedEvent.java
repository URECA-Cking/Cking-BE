package kr.co.cking.event.application.dto;

import java.io.Serializable;
import java.time.Instant;
import java.util.List;

import kr.co.cking.event.domain.Event;
import kr.co.cking.event.domain.EventStatus;
import kr.co.cking.event.domain.PrizeConfig;

public record CachedEvent(
        Long eventId,
        Long creatorId,
        String title,
        String description,
        Instant startAt,
        Instant endAt,
        Integer winnerCount,
        String drawMethod,
        String prizeAlgorithmVersion,
        EventStatus status,
        List<PrizeConfig> prizes
) implements Serializable {

    public CachedEvent(Long eventId, Long creatorId, String title, String description, Instant startAt, Instant endAt,
                       Integer winnerCount, String drawMethod, EventStatus status) {
        this(eventId, creatorId, title, description, startAt, endAt, winnerCount, drawMethod,
                "PRIZE_WEIGHTED_V1", status, List.of());
    }

    public CachedEvent {
        prizeAlgorithmVersion = prizeAlgorithmVersion == null ? "PRIZE_WEIGHTED_V1" : prizeAlgorithmVersion;
        prizes = prizes == null ? List.of() : List.copyOf(prizes);
    }

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
                event.getPrizeAlgorithmVersion(),
                event.getStatus(),
                event.getPrizeConfigs()
        );
    }
}
