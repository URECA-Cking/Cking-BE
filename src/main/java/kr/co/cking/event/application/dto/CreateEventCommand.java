package kr.co.cking.event.application.dto;

import kr.co.cking.event.domain.DrawMethod;
import kr.co.cking.event.domain.PrizeConfig;
import kr.co.cking.drawing.domain.prize.PrizeAllocationAlgorithmVersion;

import java.time.Instant;
import java.util.List;

/** Creator Event 생성에 필요한 검증 완료 전 명령 데이터를 전달한다. */
public record CreateEventCommand(
        Long userId,
        String requestId,
        String title,
        String description,
        Instant startAt,
        Instant endAt,
        int winnerCount,
        DrawMethod drawMethod,
        PrizeAllocationAlgorithmVersion prizeAlgorithmVersion,
        List<PrizeConfig> prizes,
        /** 업로드한 배너 키(이슈 #748). null이면 배너 없이 만든다. 같은 requestId 재시도의 본문 비교에 포함된다. */
        String bannerImageKey
) {
    public CreateEventCommand(Long userId, String requestId, String title, String description, Instant startAt,
                              Instant endAt, int winnerCount, DrawMethod drawMethod,
                              PrizeAllocationAlgorithmVersion prizeAlgorithmVersion, List<PrizeConfig> prizes) {
        this(userId, requestId, title, description, startAt, endAt, winnerCount, drawMethod, prizeAlgorithmVersion,
                prizes, null);
    }

    public CreateEventCommand(Long userId, String requestId, String title, String description, Instant startAt,
                              Instant endAt, int winnerCount, DrawMethod drawMethod, List<PrizeConfig> prizes) {
        this(userId, requestId, title, description, startAt, endAt, winnerCount, drawMethod,
                PrizeAllocationAlgorithmVersion.PRIZE_WEIGHTED_V1, prizes);
    }

    public CreateEventCommand(Long userId, String requestId, String title, String description, Instant startAt,
                              Instant endAt, int winnerCount, DrawMethod drawMethod) {
        this(userId, requestId, title, description, startAt, endAt, winnerCount, drawMethod,
                PrizeAllocationAlgorithmVersion.PRIZE_WEIGHTED_V1, List.of());
    }
}
