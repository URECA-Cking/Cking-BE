package kr.co.cking.redraw.presentation;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/** RedrawRequest 실행을 요청하는 관리자 식별자 입력이다. */
@JsonIgnoreProperties(ignoreUnknown = false)
public record RedrawRequestExecuteRequest(
        @NotNull @Positive Long userId
) {
}
