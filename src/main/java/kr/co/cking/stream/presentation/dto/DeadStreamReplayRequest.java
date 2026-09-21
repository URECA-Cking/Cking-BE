package kr.co.cking.stream.presentation.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record DeadStreamReplayRequest(@NotNull @Positive Long userId) {
}
