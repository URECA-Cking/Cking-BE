package kr.co.cking.winner.presentation;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/** 관리자 Winner 자격 박탈 요청의 호출자 식별자와 감사 사유다. */
public record AdminWinnerDisqualifyRequest(
        @Schema(description = "자격 박탈을 처리하는 관리자 Member ID", example = "1")
        @NotNull @Positive Long userId,
        @Schema(description = "자격 박탈 사유", example = "이벤트 참여 조건을 충족하지 않았습니다.")
        @NotBlank String reason
) {
}
