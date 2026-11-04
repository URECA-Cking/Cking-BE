package kr.co.cking.creator.presentation.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Size;
import java.util.List;
import kr.co.cking.creator.domain.CreatorApplicationForm;

public final class CreatorApplicationRequest {

    private CreatorApplicationRequest() {
    }

    public record Reject(
            @NotBlank @Size(max = 500) String rejectReason
    ) {
    }

    public record Form(
            @NotBlank String preferredActivityName,
            @NotNull @Size(min = 1, max = 3) List<@NotBlank String> categoryCodes,
            @NotBlank @Size(min = 30, max = 500) String introduction,
            @NotNull @Size(min = 1, max = 3) List<@NotBlank String> evidenceUrls,
            @NotBlank @Size(min = 50, max = 1000) String activityPlan,
            @Size(max = 5) List<@NotBlank String> platforms,
            @AssertTrue boolean agreeToCreatorPolicy
    ) {
        public CreatorApplicationForm toForm() {
            return new CreatorApplicationForm(preferredActivityName, null, categoryCodes, introduction,
                    evidenceUrls, activityPlan, platforms == null ? List.of() : platforms,
                    agreeToCreatorPolicy);
        }
    }

    public record Revision(@NotBlank @Size(max = 500) String revisionReason) {
    }
}
