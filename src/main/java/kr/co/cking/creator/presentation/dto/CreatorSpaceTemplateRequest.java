package kr.co.cking.creator.presentation.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class CreatorSpaceTemplateRequest {

    /**
     * {@code {creatorId}}를 정확히 한 번 포함해야 한다. Creator Space 생성 시점에 이 자리표시자를
     * 실제 creatorId로 치환해 slug를 만드는데, 자리표시자가 없으면 모든 Creator가 같은 slug를
     * 갖게 돼 두 번째 승인부터 {@code creator_space.slug} UNIQUE 제약 위반으로 실패한다.
     */
    private static final String SLUG_RULE_PATTERN = "^(?:(?!\\{creatorId\\}).)*\\{creatorId\\}(?:(?!\\{creatorId\\}).)*$";
    private static final String SLUG_RULE_MESSAGE = "slugRule은 {creatorId}를 정확히 한 번 포함해야 합니다.";

    /**
     * creatorId는 Long이라 최대 19자리 숫자로 치환될 수 있다. {@code {creatorId}}(11자)를 치환하면
     * 최대 8자가 늘어나므로, {@code creator_space.slug} 컬럼(VARCHAR(100))을 넘지 않으려면
     * slugRule 자체는 92자를 넘을 수 없다.
     */
    private static final int SLUG_RULE_MAX_LENGTH = 92;

    private CreatorSpaceTemplateRequest() {
    }

    public record Create(
            @NotBlank @Size(max = 500) String introText,
            @NotBlank @Size(max = 500) String profileImageUrl,
            @NotBlank @Size(max = 500) String bannerImageUrl,
            @NotBlank @Size(max = SLUG_RULE_MAX_LENGTH) @Pattern(regexp = SLUG_RULE_PATTERN, message = SLUG_RULE_MESSAGE) String slugRule,
            @NotNull Boolean homeTabEnabled,
            @NotNull Boolean missionsTabEnabled,
            @NotNull Boolean postsTabEnabled,
            @NotNull Boolean eventsTabEnabled
    ) {
    }

    public record Update(
            @NotBlank @Size(max = 500) String introText,
            @NotBlank @Size(max = 500) String profileImageUrl,
            @NotBlank @Size(max = 500) String bannerImageUrl,
            @NotBlank @Size(max = SLUG_RULE_MAX_LENGTH) @Pattern(regexp = SLUG_RULE_PATTERN, message = SLUG_RULE_MESSAGE) String slugRule,
            @NotNull Boolean homeTabEnabled,
            @NotNull Boolean missionsTabEnabled,
            @NotNull Boolean postsTabEnabled,
            @NotNull Boolean eventsTabEnabled
    ) {
    }
}
