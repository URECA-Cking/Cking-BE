package kr.co.cking.winner.presentation;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/** 관리자 Winner 수령 완료 요청의 호출자 식별자다. */
public record AdminWinnerReceiveRequest(
        @Schema(description = "수령 완료를 처리하는 관리자 Member ID", example = "1")
        @NotNull @Positive Long userId
) {
}
