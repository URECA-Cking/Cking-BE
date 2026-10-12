package kr.co.cking.creator.presentation.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreatorSpaceProfileImageRequest(@NotBlank @Size(max = 200) String imageKey) {
}
