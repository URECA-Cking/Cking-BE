package kr.co.cking.redraw.application;

import java.time.Instant;
import java.util.List;
import kr.co.cking.redraw.domain.RedrawExecutionStatus;
import kr.co.cking.redraw.domain.RedrawRequest;
import kr.co.cking.redraw.domain.RedrawRequestStatus;

/** RedrawRequest의 고정 결원과 요청·심사·실행 정보를 함께 제공하는 관리자 상세 응답이다. */
public record RedrawRequestDetailResult(
        Long redrawRequestId,
        Long eventId,
        Long originalDrawingId,
        Long redrawDrawingId,
        int vacancyCount,
        List<RedrawVacancyWinnerResult> vacancyWinners,
        RedrawRequestStatus status,
        RedrawExecutionStatus executionStatus,
        String reason,
        Long requestedBy,
        Instant requestedAt,
        Long reviewedBy,
        Instant reviewedAt,
        String rejectReason
) {

    /** 영속된 요청과 연관 조회 결과를 API가 반환할 상세 형식으로 조립한다. */
    public static RedrawRequestDetailResult from(
            RedrawRequest request,
            Long redrawDrawingId,
            List<RedrawVacancyWinnerResult> vacancyWinners
    ) {
        return new RedrawRequestDetailResult(
                request.getId(),
                request.getEventId(),
                request.getOriginalDrawingId(),
                redrawDrawingId,
                request.getVacancyCount(),
                List.copyOf(vacancyWinners),
                request.getStatus(),
                request.getExecutionStatus(),
                request.getReason(),
                request.getRequestedBy(),
                request.getRequestedAt(),
                request.getReviewedBy(),
                request.getReviewedAt(),
                request.getRejectReason()
        );
    }
}
