package kr.co.cking.redraw.presentation;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;

/** 관리자 RedrawRequest 거절 요청의 거절 사유를 전달한다. */
public record RedrawRequestRejectRequest(
        @Schema(description = "재추첨 요청 거절 사유", example = "결원 확인이 필요합니다.")
        @NotBlank @Size(max = 500) String rejectReason
) {

    /** Bean Validation 전에 거절 사유의 앞뒤 공백을 제거한다. */
    public RedrawRequestRejectRequest {
        rejectReason = rejectReason != null ? rejectReason.strip() : null;
    }

    /** 거절 계약에 없는 JSON 필드는 요청 검증 오류로 거부한다. */
    @JsonAnySetter
    public void rejectUnknownField(String fieldName, Object value) {
        throw new BusinessException(CommonErrorCode.VALIDATION_FAILED);
    }
}
