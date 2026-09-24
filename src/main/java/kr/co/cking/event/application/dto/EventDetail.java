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
        String prizeAlgorithmVersion,
        long myTicketBalance,
        long myCommonTicketBalance,
        List<PrizeResult> prizes
) {

    public EventDetail(Long eventId, Long creatorId, String title, String description, Instant startAt, Instant endAt,
                       EventStatus status, DisplayStatus displayStatus, Integer winnerCount, String drawMethod,
                       long myTicketBalance, long myCommonTicketBalance) {
        this(eventId, creatorId, title, description, startAt, endAt, status, displayStatus, winnerCount, drawMethod,
                "PRIZE_WEIGHTED_V1", myTicketBalance, myCommonTicketBalance, List.of());
    }

    public EventDetail {
        prizes = List.copyOf(prizes);
    }

    public static EventDetail of(CachedEvent event, Instant now, long myTicketBalance, long myCommonTicketBalance) {
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
                event.prizeAlgorithmVersion(),
                myTicketBalance,
                myCommonTicketBalance,
                event.prizes().stream().map(PrizeResult::from).toList()
        );
    }
}
