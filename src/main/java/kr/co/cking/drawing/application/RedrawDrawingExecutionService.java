package kr.co.cking.drawing.application;

/** 시스템4가 호출하는 시스템3 REDRAW 실행 경계다. */
public interface RedrawDrawingExecutionService {

    /** 고정 결원 수로 INITIAL Snapshot 기반 전체 재추첨을 실행한다. */
    RedrawDrawingExecutionResult execute(Long redrawRequestId, Long adminId, Long originalDrawingId, int vacancyCount);
}
