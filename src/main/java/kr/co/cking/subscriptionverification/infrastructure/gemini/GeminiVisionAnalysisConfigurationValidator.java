package kr.co.cking.subscriptionverification.infrastructure.gemini;

import org.springframework.beans.factory.InitializingBean;

/** 구독 인증 제출 기능과 Gemini Secret의 조건부 기동 계약을 검증한다. */
final class GeminiVisionAnalysisConfigurationValidator implements InitializingBean {

    private final GeminiVisionAnalysisProperties properties;
    private final boolean submissionEnabled;

    GeminiVisionAnalysisConfigurationValidator(
            GeminiVisionAnalysisProperties properties, boolean submissionEnabled) {
        this.properties = properties;
        this.submissionEnabled = submissionEnabled;
    }

    @Override
    public void afterPropertiesSet() {
        if (submissionEnabled && isBlank(properties.getApiKey())) {
            throw new IllegalStateException(
                    "YouTube 구독 인증 제출 기능을 활성화하려면 SUBSCRIPTION_GEMINI_API_KEY가 필요합니다.");
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
