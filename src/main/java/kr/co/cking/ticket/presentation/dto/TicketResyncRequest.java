package kr.co.cking.ticket.presentation.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/** #207: 관리자가 (memberId, creatorId) 잔액을 DB 기준으로 수동 재동기화할 때 쓰는 요청. */
public record TicketResyncRequest(
        @NotNull @Positive Long memberId,
        @NotNull @Positive Long creatorId,
        @NotBlank @Size(max = 500) String reason
) {
}
