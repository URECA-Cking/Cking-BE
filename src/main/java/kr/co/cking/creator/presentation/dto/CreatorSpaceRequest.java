package kr.co.cking.creator.presentation.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import kr.co.cking.creator.domain.CreatorSpaceSlugRule;

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

    /**
     * 커스텀 slug 형식(3~30자)은 여기서 검증하지 않는다. 본인이 버린 이전 slug로 되돌리는 경우에는
     * 30자를 넘는 자동 slug일 수 있어서, 형식·예약어·중복은 Application이 새 slug일 때만 검증한다(이슈 #301).
     * 여기서는 컬럼 길이만 막는다.
     */
    public record ChangeSlug(
            @NotBlank @Size(max = CreatorSpaceSlugRule.MAX_SLUG_LENGTH) String slug
    ) {
    }
}
