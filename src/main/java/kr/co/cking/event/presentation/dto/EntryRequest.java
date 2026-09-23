package kr.co.cking.event.presentation.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

import kr.co.cking.ticket.domain.CouponType;

/**
 * 응모 요청. {@code ticketCount}는 방어용 최대값(100)까지만 컨트롤러에서 사전검증하고,
 * 실제 잔액 범위 검증은 Redis Lua({@link kr.co.cking.event.application.service.EntrySpendService})가 담당한다.
 * {@code couponType}은 생략하면 CREATOR로 처리한다(이슈 #243) - 이벤트가 아니라 이 요청이
 * 어떤 응모권을 쓸지 정한다.
 */
public record EntryRequest(
        @NotNull Long userId,
        @NotNull UUID requestId,
        @NotNull @Min(1) @Max(100) Integer ticketCount,
        CouponType couponType
) {
}
