package kr.co.cking.redraw.application;

import kr.co.cking.redraw.domain.RedrawExecutionStatus;

/** 관리자 API가 반환할 RedrawRequest 실행의 최종 상태를 담는다. */
public record RedrawRequestExecutionResult(Long redrawRequestId, RedrawExecutionStatus executionStatus, Long redrawDrawingId) {
}
