package kr.co.cking.event.presentation.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/** 수동 마감 요청자 식별자다. */
public record EventCloseRequest(
        @NotNull @Positive Long userId
) {
}
