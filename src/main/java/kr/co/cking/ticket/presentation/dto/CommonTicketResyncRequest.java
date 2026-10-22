package kr.co.cking.ticket.presentation.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/** #256: 관리자가 공용 응모권 잔액을 DB 기준으로 수동 재동기화할 때 쓰는 요청. */
public record CommonTicketResyncRequest(
        @NotNull @Positive Long memberId,
        @NotBlank @Size(max = 500) String reason
) {
}
