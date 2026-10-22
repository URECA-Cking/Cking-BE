package kr.co.cking.drawing.application;

/** REDRAW 준비 Transaction이 확정한 실행 요청 또는 즉시 반환할 종결 결과다. */
record RedrawDrawingPreparation(
        DrawingRetryRequest executionRequest,
        RedrawDrawingExecutionResult terminalResult
) {

    static RedrawDrawingPreparation started(DrawingRetryRequest request) {
        return new RedrawDrawingPreparation(request, null);
    }

    static RedrawDrawingPreparation terminal(RedrawDrawingExecutionResult result) {
        return new RedrawDrawingPreparation(null, result);
    }

    boolean requiresExecution() {
        return executionRequest != null;
    }
}
