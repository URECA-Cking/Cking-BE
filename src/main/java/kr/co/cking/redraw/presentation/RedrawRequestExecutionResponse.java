package kr.co.cking.redraw.presentation;

import kr.co.cking.redraw.application.RedrawRequestExecutionResult;
import kr.co.cking.redraw.domain.RedrawExecutionStatus;

/** RedrawRequest 실행 완료 상태를 외부 API 응답으로 변환한다. */
public record RedrawRequestExecutionResponse(
        Long redrawRequestId,
        RedrawExecutionStatus executionStatus,
        Long redrawDrawingId
) {
    /** Application 실행 결과를 응답에 필요한 값으로 변환한다. */
    public static RedrawRequestExecutionResponse from(RedrawRequestExecutionResult result) {
        return new RedrawRequestExecutionResponse(result.redrawRequestId(), result.executionStatus(), result.redrawDrawingId());
    }
}
