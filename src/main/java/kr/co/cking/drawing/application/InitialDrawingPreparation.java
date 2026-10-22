package kr.co.cking.drawing.application;

/** INITIAL 실행 준비 결과로 기존 완료 응답 또는 새 실행 요청 중 하나만 보관한다. */
record InitialDrawingPreparation(
        InitialDrawingResult existingResult,
        DrawingRetryRequest executionRequest
) {

    static InitialDrawingPreparation existing(InitialDrawingResult result) {
        return new InitialDrawingPreparation(result, null);
    }

    static InitialDrawingPreparation started(DrawingRetryRequest request) {
        return new InitialDrawingPreparation(null, request);
    }

    boolean isExisting() {
        return existingResult != null;
    }
}
