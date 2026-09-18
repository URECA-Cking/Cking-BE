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

    /** INITIAL Drawing 준비 결과를 관리자 API 응답 형식으로 변환한다. */
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
