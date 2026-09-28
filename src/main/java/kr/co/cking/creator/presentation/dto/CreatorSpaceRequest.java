package kr.co.cking.creator.presentation.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public final class CreatorSpaceRequest {

    private CreatorSpaceRequest() {
    }

    /** slug는 수정 대상이 아니다. 필드 길이는 creator_space 컬럼과 같다. */
    public record UpdateProfile(
            @NotBlank @Size(max = 500) String introText,
            @NotBlank @Size(max = 500) String profileImageUrl,
            @NotBlank @Size(max = 500) String bannerImageUrl,
            @NotNull Boolean homeTabEnabled,
            @NotNull Boolean missionsTabEnabled,
            @NotNull Boolean postsTabEnabled,
            @NotNull Boolean eventsTabEnabled
    ) {
    }
}
