package kr.co.cking.quiz.grounding.gemini;

import kr.co.cking.common.ai.AiProviderCallLog;
import kr.co.cking.common.ai.AiProviderCallLog.NoResponse;
import kr.co.cking.quiz.generation.QuizProviderTimeoutException;
import kr.co.cking.quiz.grounding.GroundingPrompt;
import kr.co.cking.quiz.grounding.GroundingResponseParser;
import kr.co.cking.quiz.grounding.VideoGrounder;
import kr.co.cking.quiz.grounding.VideoGrounding;
import kr.co.cking.quiz.grounding.VideoGroundingException;
import kr.co.cking.quiz.grounding.VideoGroundingException.Failure;
import kr.co.cking.quiz.grounding.VideoGroundingValidator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

/**
 * Gemini Interactions API({@code POST /interactions})로 YouTube 영상을 분석한다(이슈 #524).
 *
 * <p>검증된 videoId로 만든 watch URL만 영상 입력으로 보내고, 구조화 출력({@code response_format})으로 응답 schema를
 * 강제한다. {@code store: false}로 Interaction 객체를 나중에 조회할 수 있게 저장하지 않도록 요청한다. 이것이
 * Provider의 모든 데이터 보관을 끈다는 뜻은 아니다.
 *
 * <p>429·5xx만 최대 시도 횟수까지 지수 대기 후 재시도한다. 시간 초과는 재시도하지 않고
 * {@link QuizProviderTimeoutException}, 그 밖의 오류는 {@link VideoGroundingException}이다. 예외와 로그에는 Provider
 * 응답 원문·API key·contentText를 남기지 않는다.
 */
@Slf4j
public class GeminiVideoGrounder implements VideoGrounder {

    private static final Pattern VIDEO_ID = Pattern.compile("[A-Za-z0-9_-]{11}");
    private static final String WATCH_URL = "https://www.youtube.com/watch?v=";

    private final RestClient restClient;
    private final GeminiGroundingProperties properties;
    private final ObjectMapper objectMapper;
    private final GroundingResponseParser parser;
    private final VideoGroundingValidator validator;
    private final Sleeper sleeper;

    public GeminiVideoGrounder(RestClient restClient, GeminiGroundingProperties properties, ObjectMapper objectMapper) {
        this(restClient, properties, objectMapper, duration -> Thread.sleep(duration.toMillis()));
    }

    GeminiVideoGrounder(RestClient restClient, GeminiGroundingProperties properties, ObjectMapper objectMapper,
                        Sleeper sleeper) {
        this.restClient = restClient;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.parser = new GroundingResponseParser(objectMapper);
        this.validator = new VideoGroundingValidator();
        this.sleeper = sleeper;
    }

    @Override
    public VideoGrounding ground(String videoId) {
        if (videoId == null || !VIDEO_ID.matcher(videoId).matches()) {
            throw new IllegalArgumentException("YouTube videoId 형식이 아닙니다.");
        }
        if (properties.getApiKey() == null || properties.getApiKey().isBlank()) {
            throw new VideoGroundingException(Failure.NOT_CONFIGURED);
        }
        String model = properties.getModel();
        Response response = send(requestBody(WATCH_URL + videoId, model));
        String outputText = outputText(response);
        return validator.validate(parser.parse(outputText, GroundingPrompt.VERSION, model));
    }

    private Response send(Map<String, Object> body) {
        int maxAttempts = Math.max(1, properties.getMaxAttempts());
        for (int attempt = 1; ; attempt++) {
            AiProviderCallLog.Call call = AiProviderCallLog.start(AiProviderCallLog.Provider.GEMINI, "quiz_grounding",
                    properties.getModel());
            try {
                String responseBody = restClient.post()
                        .uri("/interactions")
                        .header("x-goog-api-key", properties.getApiKey())
                        .body(body)
                        .retrieve()
                        .body(String.class);
                return new Response(responseBody, call, attempt);
            } catch (RestClientResponseException exception) {
                int status = exception.getStatusCode().value();
                if (!isRetryable(status)) {
                    call.httpError(exception, attempt, false);
                    log.warn("영상 Grounding 요청이 거부되었습니다. status={}", status);
                    throw new VideoGroundingException(Failure.REJECTED);
                }
                if (attempt >= maxAttempts) {
                    call.httpError(exception, attempt, false);
                    log.warn("영상 Grounding 재시도를 모두 소진했습니다. status={}, attempts={}", status, attempt);
                    throw new VideoGroundingException(Failure.PROVIDER_ERROR);
                }
                call.httpError(exception, attempt, true);
                log.warn("영상 Grounding 요청을 재시도합니다. status={}, attempt={}", status, attempt);
                pause(attempt);
            } catch (RestClientException exception) {
                if (isTimeout(exception)) {
                    call.noResponse(NoResponse.TIMEOUT, exception, attempt, false);
                    throw new QuizProviderTimeoutException("영상 Grounding 응답 시간이 초과되었습니다.", null);
                }
                call.noResponse(NoResponse.NETWORK, exception, attempt, false);
                log.warn("영상 Grounding 네트워크 요청이 실패했습니다. type={}", exception.getClass().getSimpleName());
                throw new VideoGroundingException(Failure.PROVIDER_ERROR);
            }
        }
    }

    private Map<String, Object> requestBody(String watchUrl, String model) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("input", List.of(
                Map.of("type", "video", "uri", watchUrl),
                Map.of("type", "text", "text", GroundingPrompt.text())));
        body.put("response_format", Map.of(
                "type", "text",
                "mime_type", "application/json",
                "schema", GroundingPrompt.responseSchema()));
        if (properties.getMaxOutputTokens() != null) {
            body.put("generation_config", Map.of("max_output_tokens", properties.getMaxOutputTokens()));
        }
        body.put("store", false);
        return body;
    }

    /** 완료된 Interaction의 {@code model_output} step에 있는 text를 순서대로 잇는다. */
    private String outputText(Response response) {
        JsonNode root;
        try {
            root = objectMapper.readTree(response.body() == null ? "" : response.body());
        } catch (JacksonException exception) {
            throw invalidEnvelope(response);
        }
        if (root == null || !"completed".equals(root.path("status").asString(null))) {
            throw invalidEnvelope(response);
        }
        StringBuilder text = new StringBuilder();
        for (JsonNode step : root.path("steps")) {
            if (!"model_output".equals(step.path("type").asString(null))) {
                continue;
            }
            for (JsonNode content : step.path("content")) {
                if ("text".equals(content.path("type").asString(null)) && content.path("text").isString()) {
                    text.append(content.path("text").asString());
                }
            }
        }
        if (text.isEmpty()) {
            throw invalidEnvelope(response);
        }
        return text.toString();
    }

    /** Interaction 응답 자체를 해석하지 못했다. 그 안의 contentText·facts 검증 실패는 여기서 남기지 않는다. */
    private VideoGroundingException invalidEnvelope(Response response) {
        response.call().invalidResponse(200, response.attempt(), false);
        return new VideoGroundingException(Failure.INVALID_RESPONSE);
    }

    private boolean isRetryable(int status) {
        return status == 429 || status >= 500;
    }

    private void pause(int attempt) {
        Duration base = positiveOr(properties.getRetryBackoff(), Duration.ofSeconds(1));
        Duration max = positiveOr(properties.getRetryBackoffMax(), base);
        Duration delay = base.multipliedBy(1L << Math.min(attempt - 1, 10));
        try {
            sleeper.sleep(delay.compareTo(max) > 0 ? max : delay);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new VideoGroundingException(Failure.PROVIDER_ERROR);
        }
    }

    private Duration positiveOr(Duration value, Duration fallback) {
        return value == null || value.isNegative() || value.isZero() ? fallback : value;
    }

    private boolean isTimeout(Throwable throwable) {
        for (Throwable current = throwable; current != null; current = current.getCause()) {
            if (current instanceof SocketTimeoutException
                    || current instanceof HttpTimeoutException
                    || current instanceof TimeoutException) {
                return true;
            }
        }
        return false;
    }

    /** 재시도 대기. 테스트에서 실제로 기다리지 않도록 바꿀 수 있다. */
    /** 성공 상태로 받은 응답 본문과, envelope 해석 실패를 같은 요청의 실패로 남기기 위한 호출 정보. */
    private record Response(String body, AiProviderCallLog.Call call, int attempt) {
    }

    @FunctionalInterface
    interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;
    }
}
