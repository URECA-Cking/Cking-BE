package kr.co.cking.mission.presentation.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.util.UUID;

public record MissionCompleteRequest(
        @NotNull @Positive Long userId,
        @NotNull UUID requestId
) {
}
