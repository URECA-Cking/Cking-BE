package kr.co.cking.redraw.application;

/** 승인된 REDRAW 요청에서 시스템3 실행에 필요한 불변 식별자와 결원 수를 전달한다. */
record RedrawExecutionCommand(
        Long redrawRequestId,
        Long adminId,
        Long originalDrawingId,
        int vacancyCount
) {
}
