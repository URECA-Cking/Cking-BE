package kr.co.cking.drawing.presentation;

import kr.co.cking.drawing.application.InitialDrawingPreparation;

public record InitialDrawingResponse(
        Long eventId,
        Long snapshotId,
        int winnerCount,
        String drawMethod,
        String algorithmVersion,
        int candidateCount
) {

    public static InitialDrawingResponse from(InitialDrawingPreparation preparation) {
        return new InitialDrawingResponse(
                preparation.eventId(),
                preparation.snapshotId(),
                preparation.winnerCount(),
                preparation.drawMethod(),
                preparation.algorithmVersion(),
                preparation.candidateCount()
        );
    }
}
