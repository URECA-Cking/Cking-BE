package kr.co.cking.event;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * 응모 요청. {@code ticketCount}는 방어용 최대값(100)까지만 컨트롤러에서 사전검증하고,
 * 실제 잔액 범위 검증은 Redis Lua({@link kr.co.cking.event.application.service.EntrySpendService})가 담당한다.
 */
public record EntryRequest(
        @NotNull Long userId,
        @NotNull UUID requestId,
        @NotNull @Min(1) @Max(100) Integer ticketCount
) {
}
