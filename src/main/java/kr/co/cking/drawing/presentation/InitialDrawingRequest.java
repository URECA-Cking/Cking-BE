package kr.co.cking.drawing.presentation;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record InitialDrawingRequest(
        @NotNull @Positive Long userId
) {
}
