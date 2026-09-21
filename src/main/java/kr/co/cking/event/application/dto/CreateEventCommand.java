package kr.co.cking.event.application.dto;

import kr.co.cking.event.domain.DrawMethod;
import kr.co.cking.event.domain.PrizeConfig;

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
        List<PrizeConfig> prizes
) {
    public CreateEventCommand(Long userId, String requestId, String title, String description, Instant startAt,
                              Instant endAt, int winnerCount, DrawMethod drawMethod) {
        this(userId, requestId, title, description, startAt, endAt, winnerCount, drawMethod, List.of());
    }
}
