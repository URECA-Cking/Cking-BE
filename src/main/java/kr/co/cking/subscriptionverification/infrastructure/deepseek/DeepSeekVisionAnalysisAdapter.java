package kr.co.cking.subscriptionverification.infrastructure.deepseek;

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
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/** DeepSeek Chat Completions REST API를 VisionAnalysisPort로 변환하는 Adapter다. */
@Slf4j
public class DeepSeekVisionAnalysisAdapter implements VisionAnalysisPort {

    private final RestClient restClient;
    private final DeepSeekVisionAnalysisProperties properties;
    private final DeepSeekVisionAnalysisResponseParser responseParser;
    private final ObjectMapper objectMapper;

    public DeepSeekVisionAnalysisAdapter(
            RestClient restClient,
            DeepSeekVisionAnalysisProperties properties,
            DeepSeekVisionAnalysisResponseParser responseParser,
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
            try {
                return parseResponse(callProvider(request, attempt));
            } catch (RestClientResponseException exception) {
                int status = exception.getStatusCode().value();
                logProviderResult(status, elapsedMillis(exception), null, attempt);
                if (!isRetryableStatus(status) || attempt == maxAttempts) {
                    throw providerFailure(status, isRetryableStatus(status), exception);
                }
                pauseBeforeRetry();
            } catch (ResourceAccessException exception) {
                if (attempt == maxAttempts) {
                    throw new VisionAnalysisException(
                            VisionAnalysisFailureType.RETRYABLE,
                            isTimeout(exception) ? "DeepSeek API 응답 시간이 초과되었습니다." : "DeepSeek API 네트워크 요청이 실패했습니다.",
                            exception);
                }
                logProviderResult(isTimeout(exception) ? 408 : 0, 0L, null, attempt);
                pauseBeforeRetry();
            } catch (IllegalArgumentException exception) {
                if (attempt == maxAttempts) {
                    throw new VisionAnalysisException(
                            VisionAnalysisFailureType.RETRYABLE,
                            "DeepSeek API 응답 형식이 올바르지 않습니다.", exception);
                }
                logProviderResult(200, 0L, null, attempt);
                pauseBeforeRetry();
            } catch (RestClientException exception) {
                if (attempt == maxAttempts) {
                    throw new VisionAnalysisException(
                            VisionAnalysisFailureType.RETRYABLE,
                            "DeepSeek API 요청이 실패했습니다.", exception);
                }
                logProviderResult(0, 0L, null, attempt);
                pauseBeforeRetry();
            }
        }
        throw new VisionAnalysisException(VisionAnalysisFailureType.RETRYABLE, "DeepSeek API 요청이 실패했습니다.");
    }

    /** Provider를 한 번 호출하고 민감한 응답 본문은 기록하지 않은 채 반환한다. */
    private String callProvider(VisionAnalysisRequest request, int attempt) {
        long startedAt = System.nanoTime();
        String response = restClient.post()
                .uri(properties.getEndpoint())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.getApiKey())
                .body(DeepSeekChatCompletionRequest.from(request, properties))
                .retrieve()
                .body(String.class);
        logProviderResult(200, elapsedMillis(startedAt), extractUsage(response), attempt);
        return response;
    }

    /** Chat Completions envelope와 내부 JSON을 순서대로 검증해 Vision 결과로 만든다. */
    private VisionAnalysisResult parseResponse(String response) {
        return responseParser.parse(responseParser.extractContent(response));
    }

    /** Provider가 반환한 상태·지연 시간·usage만 민감정보 없이 기록한다. */
    private void logProviderResult(int status, long latencyMillis, Map<String, Object> usage, int attempt) {
        int promptTokens = numberValue(usage, "prompt_tokens");
        int completionTokens = numberValue(usage, "completion_tokens");
        int totalTokens = numberValue(usage, "total_tokens");
        log.info("DeepSeek VLM 호출 완료: model={}, status={}, latencyMs={}, promptTokens={}, completionTokens={}, totalTokens={}, attempt={}",
                properties.getModel(), status, latencyMillis, promptTokens, completionTokens, totalTokens, attempt);
    }

    /** 응답 envelope에서 usage 숫자만 최선으로 추출하며 실패해도 분석 결과에 영향을 주지 않는다. */
    private Map<String, Object> extractUsage(String response) {
        try {
            Map<String, Object> root = objectMapper.readValue(response, new TypeReference<>() {
            });
            Object usage = root == null ? null : root.get("usage");
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

    /** usage의 숫자 필드를 로그용 정수로 안전하게 변환한다. */
    private int numberValue(Map<String, Object> usage, String field) {
        if (usage == null || !(usage.get(field) instanceof Number value)) {
            return -1;
        }
        return value.intValue();
    }

    /** 429와 5xx만 동일 입력으로 다시 시도할 수 있는 HTTP 상태로 판단한다. */
    private boolean isRetryableStatus(int status) {
        return status == 429 || status >= 500;
    }

    /** HTTP 실패를 Processing 계층이 이해하는 재시도 가능 기술 오류로 변환한다. */
    private VisionAnalysisException providerFailure(int status, boolean retryable, Throwable cause) {
        return new VisionAnalysisException(
                retryable ? VisionAnalysisFailureType.RETRYABLE : VisionAnalysisFailureType.NON_RETRYABLE,
                "DeepSeek API 요청이 실패했습니다. status=" + status,
                cause);
    }

    /** 설정한 backoff만큼 대기하고 인터럽트 시 재시도 가능한 기술 오류로 종료한다. */
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
                    VisionAnalysisFailureType.RETRYABLE, "DeepSeek API 재시도가 중단되었습니다.", exception);
        }
    }

    /** 예외 원인 체인에 timeout 계열 예외가 있는지 확인한다. */
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

    /** 시작 시각으로부터 밀리초 단위 경과 시간을 계산한다. */
    private long elapsedMillis(long startedAt) {
        return Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
    }

    /** HTTP 예외에서 제공되지 않는 지연 시간은 0으로 기록한다. */
    private long elapsedMillis(RestClientResponseException exception) {
        return 0L;
    }

    /** 호출 직전에 API 키 누락을 비재시도 설정 오류로 막는다. */
    private void requireApiKey() {
        if (properties.getApiKey() == null || properties.getApiKey().isBlank()) {
            throw new VisionAnalysisException(
                    VisionAnalysisFailureType.NON_RETRYABLE, "DeepSeek API key가 설정되지 않았습니다.");
        }
    }
}
