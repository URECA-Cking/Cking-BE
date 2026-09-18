package kr.co.cking.drawing.application;

import kr.co.cking.drawing.domain.Drawing;
import kr.co.cking.drawing.domain.DrawingStatus;

/** INITIAL Drawing 실행 또는 완료 결과의 멱등 응답 계약. */
public record InitialDrawingResult(
        Long drawingId,
        Long eventId,
        DrawingStatus status,
        int winnerCount
) {

    public static InitialDrawingResult from(Drawing drawing) {
        if (drawing == null || drawing.getId() == null) {
            throw new IllegalArgumentException("저장된 Drawing은 필수입니다.");
        }
        return new InitialDrawingResult(
                drawing.getId(),
                drawing.getEventId(),
                drawing.getStatus(),
                drawing.getWinnerCount()
        );
    }
}
