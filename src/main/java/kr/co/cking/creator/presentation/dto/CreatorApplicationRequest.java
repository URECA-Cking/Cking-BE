package kr.co.cking.creator.presentation.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public final class CreatorApplicationRequest {

    private CreatorApplicationRequest() {
    }

    public record Reject(
            @NotBlank @Size(max = 500) String rejectReason
    ) {
    }
}
