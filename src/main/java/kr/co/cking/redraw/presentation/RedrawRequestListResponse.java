package kr.co.cking.redraw.presentation;

import java.time.Instant;
import kr.co.cking.redraw.application.RedrawRequestListResult;
import kr.co.cking.redraw.domain.RedrawExecutionStatus;
import kr.co.cking.redraw.domain.RedrawRequestStatus;

/** 관리자 RedrawRequest 목록 항목의 외부 응답 형태다. */
public record RedrawRequestListResponse(
        Long redrawRequestId,
        Long eventId,
        Long originalDrawingId,
        Long redrawDrawingId,
        int vacancyCount,
        RedrawRequestStatus status,
        RedrawExecutionStatus executionStatus,
        String reason,
        Long requestedBy,
        Instant requestedAt,
        Long reviewedBy,
        Instant reviewedAt
) {

    /** Application 목록 결과를 외부 API 응답 항목으로 변환한다. */
    public static RedrawRequestListResponse from(RedrawRequestListResult result) {
        return new RedrawRequestListResponse(
                result.redrawRequestId(), result.eventId(), result.originalDrawingId(), result.redrawDrawingId(),
                result.vacancyCount(), result.status(), result.executionStatus(), result.reason(),
                result.requestedBy(), result.requestedAt(), result.reviewedBy(), result.reviewedAt()
        );
    }
}
