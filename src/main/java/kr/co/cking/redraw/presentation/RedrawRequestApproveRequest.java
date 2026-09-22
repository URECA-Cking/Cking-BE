package kr.co.cking.redraw.presentation;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;

/** 관리자 RedrawRequest 승인 요청의 호출자 식별자를 전달한다. */
public record RedrawRequestApproveRequest(
        @Schema(description = "승인하는 관리자 Member ID", example = "1")
        @NotNull @Positive Long userId
) {

    /** 승인 계약에 없는 JSON 필드는 요청 검증 오류로 거부한다. */
    @JsonAnySetter
    public void rejectUnknownField(String fieldName, Object value) {
        throw new BusinessException(CommonErrorCode.VALIDATION_FAILED);
    }
}
