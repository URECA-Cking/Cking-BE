package kr.co.cking.drawing.application;

/** 시스템3 REDRAW 실행의 성공 또는 후보 부족 결과를 시스템4에 전달한다. */
public record RedrawDrawingExecutionResult(Long drawingId, boolean insufficientCandidates) {

    /** REDRAW Drawing 생성 성공 결과를 만든다. */
    public static RedrawDrawingExecutionResult executed(Long drawingId) {
        return new RedrawDrawingExecutionResult(drawingId, false);
    }

    /** 후보 부족으로 Drawing을 생성하지 않은 결과를 만든다. */
    public static RedrawDrawingExecutionResult noCandidates() {
        return new RedrawDrawingExecutionResult(null, true);
    }
}
