package kr.co.cking.drawing.application;

import java.util.List;

public record DrawingResultQuery(
        Long drawingId,
        List<DrawingWinnerResult> winners
) {

    public DrawingResultQuery {
        winners = List.copyOf(winners);
    }
}
