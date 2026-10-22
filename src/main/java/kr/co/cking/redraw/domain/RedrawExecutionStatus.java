package kr.co.cking.redraw.domain;

/** 승인된 재추첨 요청의 실제 Drawing 실행 상태다. */
public enum RedrawExecutionStatus {
    PENDING,
    EXECUTED,
    INSUFFICIENT_CANDIDATES,
    FAILED
}
