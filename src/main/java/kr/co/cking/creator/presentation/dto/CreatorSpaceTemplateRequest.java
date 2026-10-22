package kr.co.cking.creator.presentation.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import kr.co.cking.creator.domain.CreatorSpaceSlugRule;

public final class CreatorSpaceTemplateRequest {

    private CreatorSpaceTemplateRequest() {
    }

    public record Create(
            @NotBlank @Size(max = 500) String introText,
            @NotBlank @Size(max = 500) String profileImageUrl,
            @NotBlank @Size(max = 500) String bannerImageUrl,
            @NotBlank @Size(max = CreatorSpaceSlugRule.MAX_RULE_LENGTH)
            @Pattern(regexp = CreatorSpaceSlugRule.REGEX, message = CreatorSpaceSlugRule.MESSAGE) String slugRule
    ) {
    }

    public record Update(
            @NotBlank @Size(max = 500) String introText,
            @NotBlank @Size(max = 500) String profileImageUrl,
            @NotBlank @Size(max = 500) String bannerImageUrl,
            @NotBlank @Size(max = CreatorSpaceSlugRule.MAX_RULE_LENGTH)
            @Pattern(regexp = CreatorSpaceSlugRule.REGEX, message = CreatorSpaceSlugRule.MESSAGE) String slugRule
    ) {
    }
}
