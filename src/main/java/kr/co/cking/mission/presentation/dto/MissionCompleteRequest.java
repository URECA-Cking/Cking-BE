package kr.co.cking.mission.presentation.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record MissionCompleteRequest(
        @NotNull Long userId,
        @NotNull UUID requestId
) {
}
