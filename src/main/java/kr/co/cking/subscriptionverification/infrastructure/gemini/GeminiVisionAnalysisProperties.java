package kr.co.cking.subscriptionverification.infrastructure.gemini;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Gemini Vision Adapter가 사용할 Provider 연결과 재시도 설정이다. */
@ConfigurationProperties(prefix = "cking.verification.youtube-subscription.gemini")
public class GeminiVisionAnalysisProperties {

    private String apiKey = "";
    private String model = "gemini-3.5-flash-lite";
    private String baseUrl = "https://generativelanguage.googleapis.com/v1beta";
    private Duration connectTimeout = Duration.ofSeconds(2);
    private Duration readTimeout = Duration.ofSeconds(30);
    private int maxAttempts = 2;
    private Duration retryBackoff = Duration.ofSeconds(1);
    private int maxOutputTokens = 512;

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    public void setConnectTimeout(Duration connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    public Duration getReadTimeout() {
        return readTimeout;
    }

    public void setReadTimeout(Duration readTimeout) {
        this.readTimeout = readTimeout;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public void setMaxAttempts(int maxAttempts) {
        this.maxAttempts = maxAttempts;
    }

    public Duration getRetryBackoff() {
        return retryBackoff;
    }

    public void setRetryBackoff(Duration retryBackoff) {
        this.retryBackoff = retryBackoff;
    }

    public int getMaxOutputTokens() {
        return maxOutputTokens;
    }

    public void setMaxOutputTokens(int maxOutputTokens) {
        this.maxOutputTokens = maxOutputTokens;
    }
}
