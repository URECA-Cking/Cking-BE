package kr.co.cking.subscriptionverification.infrastructure.gemini;

import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeoutException;

import kr.co.cking.subscriptionverification.application.vision.VisionAnalysisException;
import kr.co.cking.subscriptionverification.application.vision.VisionAnalysisFailureType;
import kr.co.cking.subscriptionverification.application.vision.VisionAnalysisPort;
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
                logProviderResult(status, elapsedMillis(startedAt), null, attempt);
                if (!isRetryableStatus(status) || attempt == maxAttempts) {
                    throw providerFailure(status, isRetryableStatus(status));
                }
                pauseBeforeRetry();
            } catch (ResourceAccessException exception) {
                logProviderResult(isTimeout(exception) ? 408 : 0, elapsedMillis(startedAt), null, attempt);
                if (attempt == maxAttempts) {
                    throw new VisionAnalysisException(
                            VisionAnalysisFailureType.RETRYABLE,
                            isTimeout(exception) ? "Gemini API 응답 시간이 초과되었습니다." : "Gemini API 네트워크 요청이 실패했습니다.",
                            exception);
                }
                pauseBeforeRetry();
            } catch (GeminiResponseParseException exception) {
                logProviderResult(
                        200,
                        elapsedMillis(startedAt),
                        response == null ? null : extractUsage(response),
                        attempt);
                if (attempt == maxAttempts) {
                    throw new VisionAnalysisException(
                            VisionAnalysisFailureType.RETRYABLE,
                            "Gemini API 응답 형식이 올바르지 않습니다.", exception);
                }
                pauseBeforeRetry();
            } catch (IllegalArgumentException exception) {
                logProviderResult(0, elapsedMillis(startedAt), null, attempt);
                throw new VisionAnalysisException(
                        VisionAnalysisFailureType.NON_RETRYABLE,
                        "Gemini API 요청 설정이 올바르지 않습니다.", exception);
            } catch (RestClientException exception) {
                logProviderResult(0, elapsedMillis(startedAt), null, attempt);
                if (attempt == maxAttempts) {
                    throw new VisionAnalysisException(
                            VisionAnalysisFailureType.RETRYABLE,
                            "Gemini API 요청이 실패했습니다.", exception);
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
    private VisionAnalysisException providerFailure(int status, boolean retryable) {
        return new VisionAnalysisException(
                retryable ? VisionAnalysisFailureType.RETRYABLE : VisionAnalysisFailureType.NON_RETRYABLE,
                "Gemini API 요청이 실패했습니다. status=" + status);
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
