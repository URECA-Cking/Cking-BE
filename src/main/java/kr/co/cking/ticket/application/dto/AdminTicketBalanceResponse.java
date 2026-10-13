package kr.co.cking.ticket.application.dto;

import java.time.Instant;

import io.swagger.v3.oas.annotations.media.Schema;
import kr.co.cking.ticket.domain.CouponType;

/** 관리자에게 Redis·MySQL 잔액의 관측값과 각 저장소의 존재 여부를 전달한다. */
public record AdminTicketBalanceResponse(
        @Schema(description = "조회 대상 회원 ID")
        Long memberId,
        @Schema(description = "크리에이터 응모권이면 크리에이터 ID, 공용 응모권이면 null", nullable = true)
        Long creatorId,
        @Schema(description = "응모권 종류")
        CouponType couponType,
        @Schema(description = "Redis 키가 없으면 null", nullable = true)
        Long redisBalance,
        @Schema(description = "MySQL Balance 행이 없으면 null", nullable = true)
        Long mysqlBalance,
        @Schema(description = "Redis 잔액 키 존재 여부")
        boolean redisKeyExists,
        @Schema(description = "MySQL Balance 행 존재 여부")
        boolean mysqlBalanceRowExists,
        @Schema(description = "두 잔액이 모두 있을 때 Redis − MySQL, 그 외에는 null", nullable = true)
        Long balanceDifference,
        @Schema(description = "두 저장소의 조회를 마친 시각(UTC)")
        Instant observedAt
) {
}
