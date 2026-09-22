package kr.co.cking.drawing.presentation;

import kr.co.cking.drawing.application.DrawingRetryResult;
import kr.co.cking.drawing.domain.DrawingStatus;
import kr.co.cking.drawing.domain.DrawingType;

/** Drawing Retry 완료 응답이다. */
public record DrawingRetryResponse(
        Long drawingId,
        Long eventId,
        DrawingType drawType,
        DrawingStatus status,
        int attemptCount,
        int winnerCount
) {

    public static DrawingRetryResponse from(DrawingRetryResult result) {
        return new DrawingRetryResponse(
                result.drawingId(), result.eventId(), result.drawType(), result.status(),
                result.attemptCount(), result.winnerCount()
        );
    }
}
