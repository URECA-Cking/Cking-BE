package kr.co.cking.subscriptionverification.infrastructure.gemini;

import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.concurrent.TimeoutException;

import kr.co.cking.subscriptionverification.application.vision.VisionAnalysisException;
import kr.co.cking.subscriptionverification.application.vision.VisionAnalysisFailureType;
import kr.co.cking.subscriptionverification.application.vision.VisionAnalysisPort;
import kr.co.cking.subscriptionverification.application.vision.VisionProviderErrorDetails;
import kr.co.cking.subscriptionverification.application.vision.VisionAnalysisRequest;
import kr.co.cking.subscriptionverification.application.vision.VisionAnalysisResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/** Gemini generateContent REST API를 VisionAnalysisPort로 변환하는 Adapter다. */
@Slf4j
public class GeminiVisionAnalysisAdapter implements VisionAnalysisPort {

    private static final Pattern SAFE_PROVIDER_TOKEN = Pattern.compile("[A-Z][A-Z0-9_]{0,79}");

    private final RestClient restClient;
    private final GeminiVisionAnalysisProperties properties;
    private final GeminiVisionAnalysisResponseParser responseParser;
    private final ObjectMapper objectMapper;

    public GeminiVisionAnalysisAdapter(
            RestClient restClient,
            GeminiVisionAnalysisProperties properties,
            GeminiVisionAnalysisResponseParser responseParser,
            ObjectMapper objectMapper) {
        this.restClient = restClient;
        this.properties = properties;
        this.responseParser = responseParser;
        this.objectMapper = objectMapper;
    }

    /** 정규화 JPEG를 분석하고 Provider 오류를 재시도 가능 여부가 있는 기술 오류로 변환한다. */
    @Override
    public VisionAnalysisResult analyze(VisionAnalysisRequest request) {
        requireApiKey();
        int maxAttempts = Math.max(1, properties.getMaxAttempts());
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            long startedAt = System.nanoTime();
            String response = null;
            try {
                response = callProvider(request);
                VisionAnalysisResult result = parseResponse(response);
                logProviderResult(200, elapsedMillis(startedAt), extractUsage(response), attempt);
                return result;
            } catch (RestClientResponseException exception) {
                int status = exception.getStatusCode().value();
                VisionProviderErrorDetails error = extractProviderError(status, exception.getResponseBodyAsString());
                logProviderFailure(error, isRetryableStatus(status), "HTTP_RESPONSE", elapsedMillis(startedAt), attempt);
                if (!isRetryableStatus(status) || attempt == maxAttempts) {
                    throw providerFailure(error, isRetryableStatus(status));
                }
                pauseBeforeRetry();
            } catch (ResourceAccessException exception) {
                boolean timeout = isTimeout(exception);
                VisionProviderErrorDetails error = new VisionProviderErrorDetails(
                        timeout ? 408 : 0,
                        VisionProviderErrorDetails.UNKNOWN_CODE,
                        VisionProviderErrorDetails.UNKNOWN,
                        timeout ? "TIMEOUT" : "NETWORK_ERROR");
                logProviderFailure(
                        error,
                        true,
                        timeout ? "TIMEOUT" : "NETWORK",
                        elapsedMillis(startedAt),
                        attempt);
                if (attempt == maxAttempts) {
                    throw new VisionAnalysisException(
                            VisionAnalysisFailureType.RETRYABLE,
                            timeout ? "Gemini API 응답 시간이 초과되었습니다." : "Gemini API 네트워크 요청이 실패했습니다.",
                            error,
                            exception);
                }
                pauseBeforeRetry();
            } catch (GeminiResponseParseException exception) {
                VisionProviderErrorDetails error = new VisionProviderErrorDetails(
                        200,
                        VisionProviderErrorDetails.UNKNOWN_CODE,
                        VisionProviderErrorDetails.UNKNOWN,
                        "RESPONSE_PARSE_ERROR");
                logProviderFailure(
                        error,
                        true,
                        "RESPONSE_PARSE",
                        elapsedMillis(startedAt),
                        attempt);
                if (attempt == maxAttempts) {
                    throw new VisionAnalysisException(
                            VisionAnalysisFailureType.RETRYABLE,
                            "Gemini API 응답 형식이 올바르지 않습니다.", error, exception);
                }
                pauseBeforeRetry();
            } catch (IllegalArgumentException exception) {
                VisionProviderErrorDetails error = new VisionProviderErrorDetails(
                        0,
                        VisionProviderErrorDetails.UNKNOWN_CODE,
                        VisionProviderErrorDetails.UNKNOWN,
                        "CLIENT_CONFIGURATION");
                logProviderFailure(
                        error,
                        false,
                        "CLIENT_CONFIGURATION",
                        elapsedMillis(startedAt),
                        attempt);
                throw new VisionAnalysisException(
                        VisionAnalysisFailureType.NON_RETRYABLE,
                        "Gemini API 요청 설정이 올바르지 않습니다.", error, exception);
            } catch (RestClientException exception) {
                VisionProviderErrorDetails error = new VisionProviderErrorDetails(
                        0,
                        VisionProviderErrorDetails.UNKNOWN_CODE,
                        VisionProviderErrorDetails.UNKNOWN,
                        "CLIENT_ERROR");
                logProviderFailure(
                        error,
                        true,
                        "CLIENT",
                        elapsedMillis(startedAt),
                        attempt);
                if (attempt == maxAttempts) {
                    throw new VisionAnalysisException(
                            VisionAnalysisFailureType.RETRYABLE,
                            "Gemini API 요청이 실패했습니다.", error, exception);
                }
                pauseBeforeRetry();
            }
        }
        throw new VisionAnalysisException(VisionAnalysisFailureType.RETRYABLE, "Gemini API 요청이 실패했습니다.");
    }

    private String callProvider(VisionAnalysisRequest request) {
        return restClient.post()
                .uri(uriBuilder -> uriBuilder
                        .path("/models/{model}:generateContent")
                        .build(properties.getModel()))
                .header("x-goog-api-key", properties.getApiKey())
                .body(GeminiGenerateContentRequest.from(request, properties))
                .retrieve()
                .body(String.class);
    }

    private VisionAnalysisResult parseResponse(String response) {
        return responseParser.parse(responseParser.extractContent(response));
    }

    private void logProviderResult(int status, long latencyMillis, Map<String, Object> usage, int attempt) {
        int promptTokens = numberValue(usage, "promptTokenCount");
        int completionTokens = numberValue(usage, "candidatesTokenCount");
        int totalTokens = numberValue(usage, "totalTokenCount");
        log.info("Gemini VLM 호출 완료: model={}, status={}, latencyMs={}, promptTokens={}, completionTokens={}, totalTokens={}, attempt={}",
                properties.getModel(), status, latencyMillis, promptTokens, completionTokens, totalTokens, attempt);
    }

    /** Provider error body의 allow-list 항목만 구조화해 남기고 message·body 전문은 폐기한다. */
    private VisionProviderErrorDetails extractProviderError(int httpStatus, String responseBody) {
        try {
            Map<String, Object> root = objectMapper.readValue(responseBody, new TypeReference<>() {
            });
            if (root == null || !(root.get("error") instanceof Map<?, ?> error)) {
                return unknownProviderError(httpStatus);
            }
            return new VisionProviderErrorDetails(
                    httpStatus,
                    providerCode(error.get("code")),
                    safeToken(error.get("status")),
                    detailReason(error.get("details")));
        } catch (JacksonException | ClassCastException exception) {
            return unknownProviderError(httpStatus);
        }
    }

    private VisionProviderErrorDetails unknownProviderError(int httpStatus) {
        return new VisionProviderErrorDetails(
                httpStatus,
                VisionProviderErrorDetails.UNKNOWN_CODE,
                VisionProviderErrorDetails.UNKNOWN,
                VisionProviderErrorDetails.UNKNOWN);
    }

    private int providerCode(Object value) {
        if (!(value instanceof Number number)) {
            return VisionProviderErrorDetails.UNKNOWN_CODE;
        }
        int code = number.intValue();
        return code >= 100 && code <= 599 ? code : VisionProviderErrorDetails.UNKNOWN_CODE;
    }

    private String detailReason(Object value) {
        if (!(value instanceof Iterable<?> details)) {
            return VisionProviderErrorDetails.UNKNOWN;
        }
        for (Object detail : details) {
            if (detail instanceof Map<?, ?> map) {
                String reason = safeToken(map.get("reason"));
                if (!VisionProviderErrorDetails.UNKNOWN.equals(reason)) {
                    return reason;
                }
            }
        }
        return VisionProviderErrorDetails.UNKNOWN;
    }

    private String safeToken(Object value) {
        if (!(value instanceof String token) || !SAFE_PROVIDER_TOKEN.matcher(token).matches()) {
            return VisionProviderErrorDetails.UNKNOWN;
        }
        return token;
    }

    private void logProviderFailure(
            VisionProviderErrorDetails error,
            boolean retryable,
            String failureKind,
            long latencyMillis,
            int attempt
    ) {
        log.warn("Gemini VLM 호출 실패: model={}, httpStatus={}, providerCode={}, providerStatus={}, providerReason={}, retryable={}, failureKind={}, latencyMs={}, attempt={}",
                properties.getModel(),
                error.httpStatus(),
                error.providerCode(),
                error.providerStatus(),
                error.providerReason(),
                retryable,
                failureKind,
                latencyMillis,
                attempt);
    }

    private Map<String, Object> extractUsage(String response) {
        try {
            Map<String, Object> root = objectMapper.readValue(response, new TypeReference<>() {
            });
            Object usage = root == null ? null : root.get("usageMetadata");
            if (usage instanceof Map<?, ?> value) {
                @SuppressWarnings("unchecked")
                Map<String, Object> typed = (Map<String, Object>) value;
                return typed;
            }
        } catch (JacksonException | ClassCastException ignored) {
            // Usage 관측 실패는 모델 응답 Parser가 별도로 기술 오류로 분류한다.
        }
        return null;
    }

    private int numberValue(Map<String, Object> usage, String field) {
        if (usage == null || !(usage.get(field) instanceof Number value)) {
            return -1;
        }
        return value.intValue();
    }

    private boolean isRetryableStatus(int status) {
        return status == 429 || status >= 500;
    }

    /** Provider 응답 본문을 가진 HTTP 예외를 cause로 연결하지 않고 상태 코드만 전달한다. */
    private VisionAnalysisException providerFailure(VisionProviderErrorDetails error, boolean retryable) {
        return new VisionAnalysisException(
                retryable ? VisionAnalysisFailureType.RETRYABLE : VisionAnalysisFailureType.NON_RETRYABLE,
                "Gemini API 요청이 실패했습니다. status=" + error.httpStatus(),
                error);
    }

    private void pauseBeforeRetry() {
        Duration backoff = properties.getRetryBackoff();
        long millis = backoff == null || backoff.isNegative() || backoff.isZero() ? 0L : backoff.toMillis();
        try {
            if (millis > 0) {
                Thread.sleep(millis);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new VisionAnalysisException(
                    VisionAnalysisFailureType.RETRYABLE, "Gemini API 재시도가 중단되었습니다.", exception);
        }
    }

    private boolean isTimeout(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof TimeoutException
                    || current instanceof SocketTimeoutException
                    || current instanceof HttpTimeoutException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private long elapsedMillis(long startedAt) {
        return Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
    }

    private void requireApiKey() {
        if (properties.getApiKey() == null || properties.getApiKey().isBlank()) {
            throw new VisionAnalysisException(
                    VisionAnalysisFailureType.NON_RETRYABLE, "Gemini API key가 설정되지 않았습니다.");
        }
    }
}
