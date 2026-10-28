package kr.co.cking.upload.presentation.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import kr.co.cking.upload.domain.ImageUploadPurpose;

public record ImageUploadCreateRequest(
        @NotNull ImageUploadPurpose purpose,
        @NotBlank String contentType,
        @Positive long size
) {
}
