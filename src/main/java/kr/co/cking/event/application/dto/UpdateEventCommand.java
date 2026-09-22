package kr.co.cking.event.application.dto;

import kr.co.cking.event.domain.DrawMethod;
import kr.co.cking.event.domain.PrizeConfig;
import kr.co.cking.drawing.domain.prize.PrizeAllocationAlgorithmVersion;

import java.time.Instant;
import java.util.List;

/** Creator Event 수정에 필요한 검증 전 명령 데이터를 전달한다. */
public record UpdateEventCommand(
        Long userId,
        Long eventId,
        String title,
        String description,
        Instant startAt,
        Instant endAt,
        int winnerCount,
        DrawMethod drawMethod,
        PrizeAllocationAlgorithmVersion prizeAlgorithmVersion,
        List<PrizeConfig> prizes
) {
    public UpdateEventCommand(Long userId, Long eventId, String title, String description, Instant startAt,
                              Instant endAt, int winnerCount, DrawMethod drawMethod, List<PrizeConfig> prizes) {
        this(userId, eventId, title, description, startAt, endAt, winnerCount, drawMethod,
                PrizeAllocationAlgorithmVersion.PRIZE_WEIGHTED_V1, prizes);
    }

    public UpdateEventCommand(Long userId, Long eventId, String title, String description, Instant startAt,
                              Instant endAt, int winnerCount, DrawMethod drawMethod) {
        this(userId, eventId, title, description, startAt, endAt, winnerCount, drawMethod,
                PrizeAllocationAlgorithmVersion.PRIZE_WEIGHTED_V1, List.of());
    }
}
