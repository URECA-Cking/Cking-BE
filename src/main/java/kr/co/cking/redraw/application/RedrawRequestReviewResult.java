package kr.co.cking.redraw.application;

import java.time.Instant;
import kr.co.cking.redraw.domain.RedrawExecutionStatus;
import kr.co.cking.redraw.domain.RedrawRequest;
import kr.co.cking.redraw.domain.RedrawRequestStatus;

/** 심사 완료된 RedrawRequest의 상태와 심사 이력을 Controller에 전달한다. */
public record RedrawRequestReviewResult(
        Long redrawRequestId,
        RedrawRequestStatus status,
        RedrawExecutionStatus executionStatus,
        Long reviewedBy,
        Instant reviewedAt,
        String rejectReason
) {

    /** 영속된 RedrawRequest에서 심사 응답에 필요한 불변 값을 만든다. */
    public static RedrawRequestReviewResult from(RedrawRequest request) {
        return new RedrawRequestReviewResult(
                request.getId(), request.getStatus(), request.getExecutionStatus(), request.getReviewedBy(),
                request.getReviewedAt(), request.getRejectReason()
        );
    }
}
