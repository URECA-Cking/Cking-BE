package kr.co.cking.drawing.presentation;

import kr.co.cking.drawing.domain.Drawing;
import kr.co.cking.drawing.domain.DrawingVisibility;

public record DrawingPublishResponse(
        Long drawingId,
        Long eventId,
        DrawingVisibility visibility
) {

    public static DrawingPublishResponse from(Drawing drawing) {
        return new DrawingPublishResponse(drawing.getId(), drawing.getEventId(), drawing.getVisibility());
    }
}
