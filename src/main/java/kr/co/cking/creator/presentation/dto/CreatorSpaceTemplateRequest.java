package kr.co.cking.creator.presentation.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public final class CreatorSpaceTemplateRequest {

    private CreatorSpaceTemplateRequest() {
    }

    public record Create(
            @NotNull Long userId,
            @NotBlank @Size(max = 500) String introText,
            @NotBlank @Size(max = 500) String profileImageUrl,
            @NotBlank @Size(max = 500) String bannerImageUrl,
            @NotBlank @Size(max = 100) String slugRule,
            @NotNull Boolean homeTabEnabled,
            @NotNull Boolean missionsTabEnabled,
            @NotNull Boolean postsTabEnabled,
            @NotNull Boolean eventsTabEnabled
    ) {
    }

    public record Update(
            @NotNull Long userId,
            @NotBlank @Size(max = 500) String introText,
            @NotBlank @Size(max = 500) String profileImageUrl,
            @NotBlank @Size(max = 500) String bannerImageUrl,
            @NotBlank @Size(max = 100) String slugRule,
            @NotNull Boolean homeTabEnabled,
            @NotNull Boolean missionsTabEnabled,
            @NotNull Boolean postsTabEnabled,
            @NotNull Boolean eventsTabEnabled
    ) {
    }

    public record Activate(@NotNull Long userId) {
    }
}
