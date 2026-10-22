package kr.co.cking.subscriptionverification.application.vision;

import java.util.Objects;

/** Provider 장애를 사용자 인증 실패와 분리해 Processing Retry 경로로 전달한다. */
public class VisionAnalysisException extends RuntimeException {

    private final VisionAnalysisFailureType failureType;
    private final VisionProviderErrorDetails providerErrorDetails;

    public VisionAnalysisException(VisionAnalysisFailureType failureType, String message) {
        this(failureType, message, VisionProviderErrorDetails.unavailable(), null);
    }

    public VisionAnalysisException(
            VisionAnalysisFailureType failureType,
            String message,
            Throwable cause) {
        this(failureType, message, VisionProviderErrorDetails.unavailable(), cause);
    }

    /** Provider 원문 대신 안전하게 정규화한 오류 분류를 함께 전달한다. */
    public VisionAnalysisException(
            VisionAnalysisFailureType failureType,
            String message,
            VisionProviderErrorDetails providerErrorDetails
    ) {
        this(failureType, message, providerErrorDetails, null);
    }

    public VisionAnalysisException(
            VisionAnalysisFailureType failureType,
            String message,
            VisionProviderErrorDetails providerErrorDetails,
            Throwable cause
    ) {
        super(message, cause);
        this.failureType = Objects.requireNonNull(failureType, "failureType");
        this.providerErrorDetails = Objects.requireNonNull(providerErrorDetails, "providerErrorDetails");
    }

    public VisionAnalysisFailureType failureType() {
        return failureType;
    }

    public boolean isRetryable() {
        return failureType == VisionAnalysisFailureType.RETRYABLE;
    }

    public VisionProviderErrorDetails providerErrorDetails() {
        return providerErrorDetails;
    }
}
