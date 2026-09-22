package kr.co.cking.drawing.presentation;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/** 관리자 Drawing Retry 요청 본문이다. */
public record DrawingRetryRequest(
        @NotNull @Positive Long userId
) {
}
