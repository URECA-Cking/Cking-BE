package kr.co.cking.subscriptionverification.application.vision;

/** Provider Adapter가 Processing 계층에 전달할 기술 실패 분류다. */
public enum VisionAnalysisFailureType {
    /** timeout·429·5xx처럼 처리 시도 상한 안에서 재시도할 수 있는 실패다. */
    RETRYABLE,

    /** 인증 실패·잘못된 요청처럼 재시도하지 않고 즉시 FAILED로 종료할 실패다. */
    NON_RETRYABLE
}
