package kr.co.cking.drawing.application;

import kr.co.cking.drawing.domain.Drawing;
import kr.co.cking.drawing.domain.DrawingStatus;
import kr.co.cking.drawing.domain.DrawingType;

/** 관리자가 요청한 Drawing Retry의 완료 결과다. */
public record DrawingRetryResult(
        Long drawingId,
        Long eventId,
        DrawingType drawType,
        DrawingStatus status,
        int attemptCount,
        int winnerCount
) {

    public static DrawingRetryResult from(Drawing drawing) {
        return new DrawingRetryResult(
                drawing.getId(), drawing.getEventId(), drawing.getDrawType(), drawing.getStatus(),
                drawing.getAttemptCount(), drawing.getWinnerCount()
        );
    }
}
