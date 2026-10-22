package kr.co.cking.winner.presentation;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 관리자 Winner 자격 박탈 요청의 감사 사유다. */
public record AdminWinnerDisqualifyRequest(
        @Schema(description = "자격 박탈 사유", example = "이벤트 참여 조건을 충족하지 않았습니다.")
        @NotBlank @Size(max = 500) String reason
) {

    /** Bean Validation 전에 저장·전달할 사유의 앞뒤 공백을 제거한다. */
    public AdminWinnerDisqualifyRequest {
        reason = reason != null ? reason.strip() : null;
    }
}
