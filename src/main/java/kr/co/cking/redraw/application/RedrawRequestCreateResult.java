package kr.co.cking.redraw.application;

import kr.co.cking.redraw.domain.RedrawExecutionStatus;
import kr.co.cking.redraw.domain.RedrawRequest;
import kr.co.cking.redraw.domain.RedrawRequestStatus;

/** RedrawRequest 생성 또는 멱등 재요청 결과를 Controller에 전달한다. */
public record RedrawRequestCreateResult(
        Long redrawRequestId,
        Long eventId,
        Long originalDrawingId,
        int vacancyCount,
        RedrawRequestStatus status,
        RedrawExecutionStatus executionStatus,
        boolean created
) {

    /** 새 생성 여부를 포함해 영속된 RedrawRequest를 응답용 불변 값으로 변환한다. */
    public static RedrawRequestCreateResult from(RedrawRequest request, boolean created) {
        return new RedrawRequestCreateResult(
                request.getId(),
                request.getEventId(),
                request.getOriginalDrawingId(),
                request.getVacancyCount(),
                request.getStatus(),
                request.getExecutionStatus(),
                created
        );
    }
}
