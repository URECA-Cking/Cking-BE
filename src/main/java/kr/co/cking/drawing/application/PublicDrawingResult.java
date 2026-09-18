package kr.co.cking.drawing.application;

import java.util.List;

public record PublicDrawingResult(Long eventId, Long drawingId, List<PublicWinnerResult> winners) {
    public PublicDrawingResult {
        winners = List.copyOf(winners);
    }
}
