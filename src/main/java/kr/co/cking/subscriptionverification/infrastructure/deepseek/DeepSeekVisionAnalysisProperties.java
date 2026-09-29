package kr.co.cking.subscriptionverification.infrastructure.deepseek;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** DeepSeek Vision Adapter가 사용할 Provider 연결과 재시도 설정이다. */
@ConfigurationProperties(prefix = "cking.verification.youtube-subscription.deepseek")
public class DeepSeekVisionAnalysisProperties {

    private String apiKey = "";
    private String model = "deepseek-flash";
    private String endpoint = "https://api.deepseek.com/chat/completions";
    private Duration connectTimeout = Duration.ofSeconds(2);
    private Duration readTimeout = Duration.ofSeconds(30);
    private int maxAttempts = 2;
    private Duration retryBackoff = Duration.ofSeconds(1);
    private int maxOutputTokens = 512;

    /** 환경변수에서 주입한 DeepSeek API 키를 반환한다. */
    public String getApiKey() {
        return apiKey;
    }

    /** 외부 설정 바인딩을 위해 DeepSeek API 키를 저장한다. */
    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    /** 호출할 DeepSeek 모델 식별자를 반환한다. */
    public String getModel() {
        return model;
    }

    /** 외부 설정 바인딩을 위해 DeepSeek 모델 식별자를 저장한다. */
    public void setModel(String model) {
        this.model = model;
    }

    /** Chat Completions 전체 endpoint를 반환한다. */
    public String getEndpoint() {
        return endpoint;
    }

    /** 외부 설정 바인딩을 위해 Chat Completions endpoint를 저장한다. */
    public void setEndpoint(String endpoint) {
        this.endpoint = endpoint;
    }

    /** TCP 연결에 허용할 최대 시간을 반환한다. */
    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    /** 외부 설정 바인딩을 위해 연결 timeout을 저장한다. */
    public void setConnectTimeout(Duration connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    /** 응답 읽기에 허용할 최대 시간을 반환한다. */
    public Duration getReadTimeout() {
        return readTimeout;
    }

    /** 외부 설정 바인딩을 위해 읽기 timeout을 저장한다. */
    public void setReadTimeout(Duration readTimeout) {
        this.readTimeout = readTimeout;
    }

    /** 최초 호출을 포함한 최대 Provider 시도 횟수를 반환한다. */
    public int getMaxAttempts() {
        return maxAttempts;
    }

    /** 외부 설정 바인딩을 위해 최대 Provider 시도 횟수를 저장한다. */
    public void setMaxAttempts(int maxAttempts) {
        this.maxAttempts = maxAttempts;
    }

    /** 재시도 사이에 대기할 backoff 시간을 반환한다. */
    public Duration getRetryBackoff() {
        return retryBackoff;
    }

    /** 외부 설정 바인딩을 위해 재시도 backoff 시간을 저장한다. */
    public void setRetryBackoff(Duration retryBackoff) {
        this.retryBackoff = retryBackoff;
    }

    /** JSON 응답 생성을 위해 Provider에 전달할 최대 출력 토큰 수를 반환한다. */
    public int getMaxOutputTokens() {
        return maxOutputTokens;
    }

    /** 외부 설정 바인딩을 위해 최대 출력 토큰 수를 저장한다. */
    public void setMaxOutputTokens(int maxOutputTokens) {
        this.maxOutputTokens = maxOutputTokens;
    }
}
