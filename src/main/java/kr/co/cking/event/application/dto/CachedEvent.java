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
        Long ownerMemberId,
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

    public CachedEvent(Long eventId, Long creatorId, Long ownerMemberId, String title, String description, Instant startAt, Instant endAt,
                       Integer winnerCount, String drawMethod, EventStatus status) {
        this(eventId, creatorId, ownerMemberId, title, description, startAt, endAt, winnerCount, drawMethod,
                "PRIZE_WEIGHTED_V1", status, List.of());
    }

    public CachedEvent {
        prizeAlgorithmVersion = prizeAlgorithmVersion == null ? "PRIZE_WEIGHTED_V1" : prizeAlgorithmVersion;
        prizes = List.copyOf(prizes);
    }

    /** 목록 캐시에는 소유권 검증에 쓰지 않는 Event 원본만 담는다. */
    public static CachedEvent from(Event event) {
        return from(event, null);
    }

    /** Creator 저장소에서 확인한 소유 회원 ID를 공개 Event 캐시 항목에 담는다. */
    public static CachedEvent from(Event event, Long ownerMemberId) {
        return new CachedEvent(
                event.getEventId(),
                event.getCreatorId(),
                ownerMemberId,
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
