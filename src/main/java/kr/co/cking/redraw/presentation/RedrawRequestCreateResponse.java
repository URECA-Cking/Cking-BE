package kr.co.cking.redraw.presentation;

import kr.co.cking.redraw.application.RedrawRequestCreateResult;
import kr.co.cking.redraw.domain.RedrawExecutionStatus;
import kr.co.cking.redraw.domain.RedrawRequestStatus;

/** 생성 또는 멱등 재요청된 RedrawRequest의 외부 응답 형태다. */
public record RedrawRequestCreateResponse(
        Long redrawRequestId,
        Long eventId,
        Long originalDrawingId,
        int vacancyCount,
        RedrawRequestStatus status,
        RedrawExecutionStatus executionStatus
) {

    /** Application 결과에서 외부에 공개할 RedrawRequest 상태만 변환한다. */
    public static RedrawRequestCreateResponse from(RedrawRequestCreateResult result) {
        return new RedrawRequestCreateResponse(
                result.redrawRequestId(), result.eventId(), result.originalDrawingId(), result.vacancyCount(),
                result.status(), result.executionStatus()
        );
    }
}
