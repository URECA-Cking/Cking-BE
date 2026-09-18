package kr.co.cking.drawing.presentation;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/** 관리자 Drawing 공개 요청에서 호출자를 식별한다. */
public record DrawingPublishRequest(
        @NotNull @Positive Long userId
) {
}
