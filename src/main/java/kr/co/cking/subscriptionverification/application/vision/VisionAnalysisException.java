package kr.co.cking.subscriptionverification.application.vision;

import java.util.Objects;

/** Provider 장애를 사용자 인증 실패와 분리해 Processing Retry 경로로 전달한다. */
public class VisionAnalysisException extends RuntimeException {

    private final VisionAnalysisFailureType failureType;

    public VisionAnalysisException(VisionAnalysisFailureType failureType, String message) {
        super(message);
        this.failureType = Objects.requireNonNull(failureType, "failureType");
    }

    public VisionAnalysisException(
            VisionAnalysisFailureType failureType,
            String message,
            Throwable cause) {
        super(message, cause);
        this.failureType = Objects.requireNonNull(failureType, "failureType");
    }

    public VisionAnalysisFailureType failureType() {
        return failureType;
    }

    public boolean isRetryable() {
        return failureType == VisionAnalysisFailureType.RETRYABLE;
    }
}
