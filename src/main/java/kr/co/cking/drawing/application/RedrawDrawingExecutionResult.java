package kr.co.cking.drawing.application;

/** 시스템3 REDRAW 실행의 성공·후보 부족·실패 결과를 시스템4에 전달한다. */
public record RedrawDrawingExecutionResult(
        Long drawingId,
        boolean insufficientCandidates,
        boolean failed
) {

    /** REDRAW Drawing 생성 성공 결과를 만든다. */
    public static RedrawDrawingExecutionResult executed(Long drawingId) {
        return new RedrawDrawingExecutionResult(drawingId, false, false);
    }

    /** 후보 부족으로 Drawing을 생성하지 않은 결과를 만든다. */
    public static RedrawDrawingExecutionResult noCandidates() {
        return new RedrawDrawingExecutionResult(null, true, false);
    }

    /** 생성된 Drawing의 실행이 실패해 동일 Drawing으로 Retry할 수 있는 결과를 만든다. */
    public static RedrawDrawingExecutionResult failed(Long drawingId) {
        return new RedrawDrawingExecutionResult(drawingId, false, true);
    }
}
