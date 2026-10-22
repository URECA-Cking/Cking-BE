package kr.co.cking.quiz.judge.openai;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Quiz Judge Provider 설정(이슈 #529). 기능 on/off와 오래된 Job 기준 시간은 같은 prefix의
 * {@code QuizJudgeEvaluationProperties}가 맡는다. Gemini 설정과 분리하고 API key는 {@code OPENAI_API_KEY}로만 주입한다. API key가 없어도
 * 기동하며 호출 시점에 검사한다. 그 밖의 값은 기동 시 검증한다.
 *
 * <p>model·timeout·token 한도는 실제 Provider 검증 전 잠정값이다. 비용 단가는 기본값이 없고, 설정하지 않으면 추정
 * 비용을 계산하지 않는다.
 */
@Validated
@ConfigurationProperties(prefix = "cking.quiz.judge")
public class OpenAiJudgeProperties {

    private static final Set<String> SUPPORTED_REASONING_EFFORTS = Set.of("medium");

    private String apiKey = "";
    @NotBlank
    private String baseUrl = "https://api.openai.com/v1";
    @NotBlank
    private String model = "gpt-6.1-sol";
    @NotBlank
    private String reasoningEffort = "medium";
    @Valid
    private MaxOutputTokens maxOutputTokens = new MaxOutputTokens();
    private Duration connectTimeout = Duration.ofSeconds(2);
    /** HTTP 요청 하나의 응답 대기 상한. 남은 전체 실행 시간보다 길게 기다리지 않는다. */
    private Duration readTimeout = Duration.ofSeconds(60);
    /** 평가 한 번(재시도 대기 포함)의 전체 실행 상한. */
    private Duration executionTimeout = Duration.ofSeconds(150);
    @Valid
    private Http http = new Http();
    /** policy v1의 reason 상한. DB VARCHAR(1000)은 저장 용량이며 정책 한도가 아니다. */
    @Positive
    @Max(200)
    private int reasonMaxLength = 200;
    @Valid
    private Pricing pricing = new Pricing();

    @AssertTrue(message = "cking.quiz.judge의 timeout과 재시도 대기는 양수이고 전체 실행 시간을 넘을 수 없습니다.")
    public boolean isDurationsValid() {
        if (!positive(connectTimeout) || !positive(readTimeout) || !positive(executionTimeout)
                || http == null || !positive(http.retryBackoff) || !positive(http.retryAfterMax)) {
            return false;
        }
        return Stream.of(connectTimeout, readTimeout, http.retryBackoff, http.retryAfterMax)
                .allMatch(value -> value.compareTo(executionTimeout) <= 0);
    }

    /**
     * 채택한 모델 설정에서 쓰는 reasoning effort만 허용한다. 다른 값은 잘못된 설정으로 보고 기동하지 않으며, 다른
     * 값이나 모델로 자동 전환하지 않는다. 허용 값을 넓히려면 실제 Provider 검증 뒤 이 목록을 바꾼다.
     */
    @AssertTrue(message = "cking.quiz.judge.reasoning-effort는 medium이어야 합니다.")
    public boolean isReasoningEffortSupported() {
        return reasoningEffort != null && SUPPORTED_REASONING_EFFORTS.contains(reasoningEffort);
    }

    /** 문항 수별 출력 token 한도. 한도를 정하지 않은 문항 수는 평가하지 않는다. */
    public int maxOutputTokensFor(int questionCount) {
        return switch (questionCount) {
            case 3 -> maxOutputTokens.threeQuestions;
            case 5 -> maxOutputTokens.fiveQuestions;
            default -> throw new IllegalArgumentException("출력 token 한도가 없는 문항 수입니다. questionCount=" + questionCount);
        };
    }

    private static boolean positive(Duration value) {
        return value != null && !value.isNegative() && !value.isZero();
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getReasoningEffort() {
        return reasoningEffort;
    }

    public void setReasoningEffort(String reasoningEffort) {
        this.reasoningEffort = reasoningEffort;
    }

    public MaxOutputTokens getMaxOutputTokens() {
        return maxOutputTokens;
    }

    public void setMaxOutputTokens(MaxOutputTokens maxOutputTokens) {
        this.maxOutputTokens = maxOutputTokens;
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

    public Duration getExecutionTimeout() {
        return executionTimeout;
    }

    public void setExecutionTimeout(Duration executionTimeout) {
        this.executionTimeout = executionTimeout;
    }

    public Http getHttp() {
        return http;
    }

    public void setHttp(Http http) {
        this.http = http;
    }

    public int getReasonMaxLength() {
        return reasonMaxLength;
    }

    public void setReasonMaxLength(int reasonMaxLength) {
        this.reasonMaxLength = reasonMaxLength;
    }

    public Pricing getPricing() {
        return pricing;
    }

    public void setPricing(Pricing pricing) {
        this.pricing = pricing;
    }

    public static class MaxOutputTokens {

        /** reasoning token을 포함한 한도. */
        @Positive
        private int threeQuestions = 8192;
        @Positive
        private int fiveQuestions = 12288;

        public int getThreeQuestions() {
            return threeQuestions;
        }

        public void setThreeQuestions(int threeQuestions) {
            this.threeQuestions = threeQuestions;
        }

        public int getFiveQuestions() {
            return fiveQuestions;
        }

        public void setFiveQuestions(int fiveQuestions) {
            this.fiveQuestions = fiveQuestions;
        }
    }

    public static class Http {

        /** 최초 요청을 포함한 HTTP 시도 횟수. 429·5xx에만 재시도하며 최대 1회다. */
        @Min(1)
        @Max(2)
        private int maxAttempts = 2;
        /** Retry-After가 없거나 잘못됐을 때의 대기. */
        private Duration retryBackoff = Duration.ofSeconds(1);
        /** 이보다 긴 Retry-After를 받으면 재시도하지 않는다. */
        private Duration retryAfterMax = Duration.ofSeconds(5);

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

        public Duration getRetryAfterMax() {
            return retryAfterMax;
        }

        public void setRetryAfterMax(Duration retryAfterMax) {
            this.retryAfterMax = retryAfterMax;
        }
    }

    /**
     * 추정 비용 계산용 단가(USD / 1M token). 기본값이 없다. 모두 비어 있으면 계산하지 않고, 하나라도 설정하면 버전과 세
     * 단가를 모두 0 이상으로 설정해야 한다.
     */
    public static class Pricing {

        private String version;
        private BigDecimal inputUsdPerMillionTokens;
        private BigDecimal cachedInputUsdPerMillionTokens;
        private BigDecimal outputUsdPerMillionTokens;

        @AssertTrue(message = "cking.quiz.judge.pricing은 모두 비우거나 버전과 세 단가(0 이상)를 모두 설정해야 합니다.")
        public boolean isConsistent() {
            if (!isConfigured()) {
                return (version == null || version.isBlank()) && inputUsdPerMillionTokens == null
                        && cachedInputUsdPerMillionTokens == null && outputUsdPerMillionTokens == null;
            }
            return Stream.of(inputUsdPerMillionTokens, cachedInputUsdPerMillionTokens, outputUsdPerMillionTokens)
                    .allMatch(price -> price != null && price.signum() >= 0);
        }

        /** 버전이 있으면 단가를 설정한 것으로 본다. 나머지 값은 {@link #isConsistent()}가 검증한다. */
        public boolean isConfigured() {
            return version != null && !version.isBlank();
        }

        public String getVersion() {
            return version;
        }

        public void setVersion(String version) {
            this.version = version;
        }

        public BigDecimal getInputUsdPerMillionTokens() {
            return inputUsdPerMillionTokens;
        }

        public void setInputUsdPerMillionTokens(BigDecimal inputUsdPerMillionTokens) {
            this.inputUsdPerMillionTokens = inputUsdPerMillionTokens;
        }

        public BigDecimal getCachedInputUsdPerMillionTokens() {
            return cachedInputUsdPerMillionTokens;
        }

        public void setCachedInputUsdPerMillionTokens(BigDecimal cachedInputUsdPerMillionTokens) {
            this.cachedInputUsdPerMillionTokens = cachedInputUsdPerMillionTokens;
        }

        public BigDecimal getOutputUsdPerMillionTokens() {
            return outputUsdPerMillionTokens;
        }

        public void setOutputUsdPerMillionTokens(BigDecimal outputUsdPerMillionTokens) {
            this.outputUsdPerMillionTokens = outputUsdPerMillionTokens;
        }
    }
}
