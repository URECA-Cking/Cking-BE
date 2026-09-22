package kr.co.cking.drawing.application;

/** Retry 실행 준비가 확정한 Drawing과 Attempt 식별자다. */
record DrawingRetryRequest(Long drawingId, int attemptNo) {
}
