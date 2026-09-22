package kr.co.cking.ticket.application.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/** #207: 관리자가 (memberId, creatorId) 잔액을 DB 기준으로 수동 재동기화할 때 쓰는 요청. */
public record TicketResyncRequest(
        @NotNull @Positive Long userId,
        @NotNull @Positive Long memberId,
        @NotNull @Positive Long creatorId,
        @NotBlank String reason
) {
}
