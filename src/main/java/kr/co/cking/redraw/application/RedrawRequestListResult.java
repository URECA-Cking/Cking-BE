package kr.co.cking.redraw.application;

import java.time.Instant;
import kr.co.cking.redraw.domain.RedrawExecutionStatus;
import kr.co.cking.redraw.domain.RedrawRequest;
import kr.co.cking.redraw.domain.RedrawRequestStatus;

/** 관리자 목록에 필요한 RedrawRequest의 요청·심사·실행 요약을 담는다. */
public record RedrawRequestListResult(
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

    /** 영속된 요청과 연결된 REDRAW Drawing ID를 관리자 목록 항목으로 변환한다. */
    public static RedrawRequestListResult from(RedrawRequest request, Long redrawDrawingId) {
        return new RedrawRequestListResult(
                request.getId(), request.getEventId(), request.getOriginalDrawingId(), redrawDrawingId,
                request.getVacancyCount(), request.getStatus(), request.getExecutionStatus(), request.getReason(),
                request.getRequestedBy(), request.getRequestedAt(), request.getReviewedBy(), request.getReviewedAt()
        );
    }
}
