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
        List<PrizeConfig> prizes,
        /** 업로드한 배너 키(이슈 #748). {@code bannerImageKeyProvided}가 false면 현재 배너를 유지하고, true에 null이면 배너를 뗀다. */
        String bannerImageKey,
        boolean bannerImageKeyProvided
) {
    public UpdateEventCommand(Long userId, Long eventId, String title, String description, Instant startAt,
                              Instant endAt, int winnerCount, DrawMethod drawMethod,
                              PrizeAllocationAlgorithmVersion prizeAlgorithmVersion, List<PrizeConfig> prizes) {
        this(userId, eventId, title, description, startAt, endAt, winnerCount, drawMethod, prizeAlgorithmVersion,
                prizes, null, false);
    }

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
