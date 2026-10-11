package kr.co.cking.quiz.grounding.gemini;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 영상 Grounding 호출 설정. Quiz 생성 설정({@code cking.quiz.gemini})과 분리하고 API key만 같은 값을 쓴다.
 * 비밀값은 환경변수로만 주입한다.
 */
@ConfigurationProperties(prefix = "cking.quiz.grounding")
public class GeminiGroundingProperties {

    private String apiKey = "";
    private String model = "gemini-3.8-flash";
    private String baseUrl = "https://generativelanguage.googleapis.com/v1beta";
    private Duration connectTimeout = Duration.ofSeconds(2);
    private Duration readTimeout = Duration.ofSeconds(30);
    /** 최초 호출을 포함한 최대 시도 횟수. 429·5xx에만 적용한다. */
    private int maxAttempts = 3;
    /** 첫 재시도 전 대기. 이후 두 배씩 늘리되 {@link #retryBackoffMax}를 넘지 않는다. */
    private Duration retryBackoff = Duration.ofSeconds(1);
    private Duration retryBackoffMax = Duration.ofSeconds(4);
    /** 비어 있으면 요청에 넣지 않고 Provider 기본값을 따른다. */
    private Integer maxOutputTokens;

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

    public Duration getRetryBackoffMax() {
        return retryBackoffMax;
    }

    public void setRetryBackoffMax(Duration retryBackoffMax) {
        this.retryBackoffMax = retryBackoffMax;
    }

    public Integer getMaxOutputTokens() {
        return maxOutputTokens;
    }

    public void setMaxOutputTokens(Integer maxOutputTokens) {
        this.maxOutputTokens = maxOutputTokens;
    }
}
