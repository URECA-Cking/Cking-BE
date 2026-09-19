package kr.co.cking.winner.presentation;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/** 당첨 포기를 요청하는 현재 호출자 식별자다. */
public record WinnerDeclineRequest(
        @Schema(description = "당첨 포기를 요청하는 Member ID", example = "1")
        @NotNull @Positive Long userId
) {
}
