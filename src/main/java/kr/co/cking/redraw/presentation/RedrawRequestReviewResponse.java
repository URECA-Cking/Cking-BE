package kr.co.cking.redraw.presentation;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import kr.co.cking.redraw.application.RedrawRequestReviewResult;
import kr.co.cking.redraw.domain.RedrawExecutionStatus;
import kr.co.cking.redraw.domain.RedrawRequestStatus;

/** 승인 또는 거절 심사 후 RedrawRequest 상태와 심사 이력을 반환한다. */
public record RedrawRequestReviewResponse(
        Long redrawRequestId,
        RedrawRequestStatus status,
        RedrawExecutionStatus executionStatus,
        Long reviewedBy,
        Instant reviewedAt,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String rejectReason
) {

    /** Application 심사 결과를 외부 API 응답 형태로 변환한다. */
    public static RedrawRequestReviewResponse from(RedrawRequestReviewResult result) {
        return new RedrawRequestReviewResponse(
                result.redrawRequestId(), result.status(), result.executionStatus(), result.reviewedBy(),
                result.reviewedAt(), result.rejectReason()
        );
    }
}
