package kr.co.cking.creator.presentation.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public final class CreatorApplicationRequest {

    private CreatorApplicationRequest() {
    }

    public record Apply(@NotNull Long userId) {
    }

    public record Review(@NotNull Long userId) {
    }

    public record Reject(
            @NotNull Long userId,
            @NotBlank @Size(max = 500) String rejectReason
    ) {
    }
}
