package kr.co.cking.creator.presentation.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import kr.co.cking.creator.domain.CreatorSpaceCustomSlug;

public final class CreatorSpaceRequest {

    private CreatorSpaceRequest() {
    }

    /** slug는 {@link ChangeSlug}로 따로 바꾼다. 필드 길이는 creator_space 컬럼과 같다. */
    public record UpdateProfile(
            @NotBlank @Size(max = 500) String introText,
            @NotBlank @Size(max = 500) String profileImageUrl,
            @NotBlank @Size(max = 500) String bannerImageUrl
    ) {
    }

    /** 형식만 여기서 검증한다. 예약어·중복은 Application이 검증한다. */
    public record ChangeSlug(
            @NotBlank @Pattern(regexp = CreatorSpaceCustomSlug.REGEX, message = CreatorSpaceCustomSlug.MESSAGE)
            String slug
    ) {
    }
}
