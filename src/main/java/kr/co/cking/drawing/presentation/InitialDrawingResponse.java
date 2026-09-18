package kr.co.cking.drawing.presentation;

import kr.co.cking.drawing.application.InitialDrawingResult;
import kr.co.cking.drawing.domain.DrawingStatus;

public record InitialDrawingResponse(
        Long drawingId,
        Long eventId,
        DrawingStatus status,
        int winnerCount
) {

    public static InitialDrawingResponse from(InitialDrawingResult result) {
        return new InitialDrawingResponse(
                result.drawingId(),
                result.eventId(),
                result.status(),
                result.winnerCount()
        );
    }
}
