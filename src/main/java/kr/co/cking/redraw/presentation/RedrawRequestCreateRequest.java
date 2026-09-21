package kr.co.cking.redraw.presentation;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;

/** 관리자 RedrawRequest 생성의 호출자·사유·멱등 키를 전달한다. */
public record RedrawRequestCreateRequest(
        @Schema(description = "재추첨 요청을 생성하는 관리자 Member ID", example = "1")
        @NotNull @Positive Long userId,
        @Schema(description = "재추첨 요청 사유", example = "당첨자 포기에 따른 재추첨이 필요합니다.")
        @NotBlank @Size(max = 500) String reason,
        @Schema(description = "동일 요청 재시도 식별용 UUID", example = "d2719c4a-1f9b-4dc4-a656-9a4bb37d8e70")
        @NotBlank @Size(max = 100)
        @Pattern(regexp = "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
        String idempotencyKey
) {

    /** Bean Validation 전에 사유의 앞뒤 공백을 제거해 Service 입력과 일치시킨다. */
    public RedrawRequestCreateRequest {
        reason = reason != null ? reason.strip() : null;
    }

    /** 서버 결정 필드를 포함한 알 수 없는 JSON 필드는 요청 계약 위반으로 거부한다. */
    @JsonAnySetter
    public void rejectUnknownField(String fieldName, Object value) {
        throw new BusinessException(CommonErrorCode.VALIDATION_FAILED);
    }
}
