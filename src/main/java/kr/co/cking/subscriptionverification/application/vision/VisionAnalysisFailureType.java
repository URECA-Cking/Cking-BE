package kr.co.cking.subscriptionverification.application.vision;

/** Provider Adapter가 Processing 계층에 전달할 기술 실패 분류다. */
public enum VisionAnalysisFailureType {
    RETRYABLE,
    NON_RETRYABLE
}
