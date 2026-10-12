package kr.co.cking.quiz.judge.openai;

import kr.co.cking.quiz.judge.JudgeUsage;
import kr.co.cking.quiz.judge.QuizJudge;
import kr.co.cking.quiz.judge.QuizJudgeEvaluation;
import kr.co.cking.quiz.judge.QuizJudgeEvaluationValidator;
import kr.co.cking.quiz.judge.QuizJudgeException;
import kr.co.cking.quiz.judge.QuizJudgeException.Failure;
import kr.co.cking.quiz.judge.QuizJudgeExecution;
import kr.co.cking.quiz.judge.QuizJudgeFingerprint;
import kr.co.cking.quiz.judge.QuizJudgeInput;
import kr.co.cking.quiz.judge.QuizJudgeInterruptedException;
import kr.co.cking.quiz.judge.QuizJudgePrompt;
import kr.co.cking.quiz.judge.QuizJudgeResponseParser;
import kr.co.cking.quiz.judge.QuizJudgeResult;
import kr.co.cking.quiz.judge.QuizJudgeSettings;
import kr.co.cking.quiz.judge.QuizJudgeTimeoutException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.InterruptedIOException;
import java.math.BigDecimal;
import java.math.MathContext;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.nio.channels.ClosedByInterruptException;
import java.time.Clock;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeoutException;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;

/**
 * OpenAI Responses API({@code POST /responses})로 Pointwise Judge를 실행한다(이슈 #529).
 *
 * <p>요청: 지시문은 {@code instructions}, 평가 대상 JSON은 {@code input}에 넣고, {@code reasoning.effort}, 문항 수별
 * {@code max_output_tokens}, strict JSON Schema({@code text.format})를 보낸다. {@code store: false}로 응답 객체를
 * 나중에 조회할 수 있게 저장하지 않도록 요청한다. 이것이 Provider의 모든 데이터 보관을 끈다는 뜻은 아니다. tool·
 * web search·background·이전 응답 연결은 보내지 않는다. 모델을 자동으로 바꾸지 않는다.
 *
 * <p>응답: 전체 실행 시간을 넘겼으면 결과를 쓰지 않는다. refusal은 {@code REFUSAL}, {@code incomplete}는
 * {@code INCOMPLETE}, 그 밖의 미완료·형식·schema·문항 번호·reason 위반은 {@code INVALID_RESPONSE}다.
 *
 * <p>재시도: 429·5xx만 최대 시도 횟수까지 한다. 유효한 Retry-After가 상한 이하면 그만큼, 상한을 넘으면 재시도하지
 * 않고, 없거나 잘못되면 기본 대기만큼 기다린다. 대기 뒤 남은 실행 시간이 없으면 다시 요청하지 않는다. 응답 시간
 * 초과는 결과를 확인할 수 없으므로 재시도하지 않는다. 각 요청의 연결·응답 대기 시간은 남은 실행 시간을 넘지 않는다.
 *
 * <p>예외와 로그에는 Provider 응답 원문·API key·평가 입력을 남기지 않는다.
 */
@Slf4j
public class OpenAiQuizJudge implements QuizJudge {

    private static final Pattern DELAY_SECONDS = Pattern.compile("\\d+");
    private static final Set<String> INCOMPLETE_REASONS = Set.of("max_output_tokens", "content_filter");
    private static final BigDecimal ONE_MILLION = BigDecimal.valueOf(1_000_000);

    private final OpenAiJudgeProperties properties;
    private final ObjectMapper objectMapper;
    private final ClientFactory clientFactory;
    private final LongSupplier nanoTime;
    private final Sleeper sleeper;
    private final Clock clock;
    private final QuizJudgeResponseParser parser;
    private final QuizJudgeEvaluationValidator validator = new QuizJudgeEvaluationValidator();

    OpenAiQuizJudge(OpenAiJudgeProperties properties, ObjectMapper objectMapper, ClientFactory clientFactory,
                    LongSupplier nanoTime, Sleeper sleeper, Clock clock) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.clientFactory = clientFactory;
        this.nanoTime = nanoTime;
        this.sleeper = sleeper;
        this.clock = clock;
        this.parser = new QuizJudgeResponseParser(objectMapper);
    }

    @Override
    public QuizJudgeResult judge(QuizJudgeInput input) {
        String model = properties.getModel();
        List<QuizJudgeExecution.Exchange> exchanges = new ArrayList<>();
        if (!isConfigured()) {
            throw new QuizJudgeException(Failure.NOT_CONFIGURED, execution(model, exchanges));
        }
        int questionCount = input.questions().size();
        QuizJudgeSettings settings = currentSettings(questionCount);
        Map<String, Object> body = requestBody(input, settings);

        long deadline = nanoTime.getAsLong() + properties.getExecutionTimeout().toNanos();
        JsonNode response = send(body, deadline, model, exchanges);
        String outputText = outputText(response, model, exchanges);
        QuizJudgeEvaluation evaluation;
        try {
            evaluation = validator.validate(parser.parse(outputText), questionCount, settings.reasonMaxLength());
        } catch (QuizJudgeException exception) {
            throw new QuizJudgeException(Failure.INVALID_RESPONSE, execution(model, exchanges));
        }
        String inputSha256 = QuizJudgeFingerprint.inputSha256(input);
        String settingsFingerprint = QuizJudgeFingerprint.settingsFingerprint(settings);
        String resultSha256 = QuizJudgeFingerprint.resultSha256(inputSha256, settingsFingerprint,
                settings.policyVersion(), evaluation);
        if (nanoTime.getAsLong() > deadline) {
            // 응답은 제때 받았어도 해석·검증·hash 계산 중 전체 실행 시간을 넘겼으면 성공으로 돌려주지 않는다.
            log.warn("Quiz Judge 전체 실행 시간을 넘겨 결과를 쓰지 않습니다.");
            throw new QuizJudgeTimeoutException(true, execution(model, exchanges));
        }
        return new QuizJudgeResult(evaluation, settings, inputSha256, settingsFingerprint, resultSha256,
                execution(model, exchanges));
    }

    @Override
    public QuizJudgeSettings currentSettings(int questionCount) {
        return QuizJudgeSettings.current(properties.getModel(), properties.getReasoningEffort(),
                properties.maxOutputTokensFor(questionCount), properties.getReasonMaxLength(), objectMapper);
    }

    @Override
    public Duration executionTimeout() {
        return properties.getExecutionTimeout();
    }

    @Override
    public boolean isConfigured() {
        return properties.getApiKey() != null && !properties.getApiKey().isBlank()
                && properties.isReasoningEffortSupported();
    }

    /** 성공 응답(2xx)의 JSON. 응답을 받은 요청은 사용량과 함께 {@code exchanges}에 남긴다. */
    private JsonNode send(Map<String, Object> body, long deadline, String model,
                          List<QuizJudgeExecution.Exchange> exchanges) {
        int maxAttempts = properties.getHttp().getMaxAttempts();
        for (int attempt = 1; ; attempt++) {
            if (Thread.currentThread().isInterrupted()) {
                throw interrupted(QuizJudgeInterruptedException.Phase.HTTP_REQUEST, model, exchanges);
            }
            Duration remaining = Duration.ofNanos(deadline - nanoTime.getAsLong());
            if (remaining.isNegative() || remaining.isZero()) {
                // 재시도 대기가 길어져 시간이 남지 않았다. 직전 요청은 429·5xx로 끝났으므로 결과는 확정된 실패다.
                throw new QuizJudgeException(Failure.PROVIDER_ERROR, execution(model, exchanges));
            }
            Duration connectTimeout = min(properties.getConnectTimeout(), remaining);
            Duration readTimeout = min(properties.getReadTimeout(), remaining);
            boolean limitedByDeadline = connectTimeout.compareTo(properties.getConnectTimeout()) < 0
                    || readTimeout.compareTo(properties.getReadTimeout()) < 0;
            String responseBody;
            try {
                responseBody = clientFactory.create(connectTimeout, readTimeout).post()
                        .uri("/responses")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.getApiKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(body)
                        .retrieve()
                        .body(String.class);
            } catch (RestClientResponseException exception) {
                int status = exception.getStatusCode().value();
                exchanges.add(new QuizJudgeExecution.Exchange(status, null, null, null));
                if (status != 429 && status < 500) {
                    log.warn("Quiz Judge 요청이 거부되었습니다. status={}", status);
                    throw new QuizJudgeException(Failure.REJECTED, execution(model, exchanges));
                }
                Optional<Duration> wait = attempt < maxAttempts
                        ? retryWait(exception.getResponseHeaders())
                        : Optional.empty();
                if (wait.isEmpty() || nanoTime.getAsLong() + wait.get().toNanos() >= deadline) {
                    log.warn("Quiz Judge 요청을 재시도하지 않습니다. status={}, attempts={}", status, attempt);
                    throw new QuizJudgeException(Failure.PROVIDER_ERROR, execution(model, exchanges));
                }
                log.warn("Quiz Judge 요청을 재시도합니다. status={}, attempt={}", status, attempt);
                pause(wait.get(), model, exchanges);
                continue;
            } catch (RestClientException exception) {
                exchanges.add(new QuizJudgeExecution.Exchange(null, null, null, null));
                // 중단으로 끊긴 I/O는 시간 초과 예외로 보일 수도 있어 중단을 먼저 판단한다.
                if (Thread.currentThread().isInterrupted() || isInterruption(exception)) {
                    throw interrupted(QuizJudgeInterruptedException.Phase.HTTP_REQUEST, model, exchanges);
                }
                if (isTimeout(exception)) {
                    log.warn("Quiz Judge 응답 시간이 초과되었습니다. limitedByDeadline={}", limitedByDeadline);
                    throw new QuizJudgeTimeoutException(limitedByDeadline, execution(model, exchanges));
                }
                log.warn("Quiz Judge 네트워크 요청이 실패했습니다. type={}", exception.getClass().getSimpleName());
                throw new QuizJudgeException(Failure.PROVIDER_ERROR, execution(model, exchanges));
            }
            JsonNode root = readResponse(responseBody);
            exchanges.add(root == null
                    ? new QuizJudgeExecution.Exchange(200, null, null, null)
                    : new QuizJudgeExecution.Exchange(200, text(root, "id"), text(root, "model"), usage(root)));
            if (Thread.currentThread().isInterrupted()) {
                throw interrupted(QuizJudgeInterruptedException.Phase.HTTP_REQUEST, model, exchanges);
            }
            if (nanoTime.getAsLong() > deadline) {
                log.warn("Quiz Judge 전체 실행 시간을 넘겨 응답을 쓰지 않습니다.");
                throw new QuizJudgeTimeoutException(true, execution(model, exchanges));
            }
            if (root == null) {
                throw new QuizJudgeException(Failure.INVALID_RESPONSE, execution(model, exchanges));
            }
            return root;
        }
    }

    /** {@code message} 출력의 {@code output_text}를 순서대로 잇는다. refusal이 하나라도 있으면 평가 거부다. */
    private String outputText(JsonNode root, String model, List<QuizJudgeExecution.Exchange> exchanges) {
        StringBuilder text = new StringBuilder();
        boolean refused = false;
        for (JsonNode item : root.path("output")) {
            if (!"message".equals(item.path("type").asString(null))) {
                continue;
            }
            for (JsonNode content : item.path("content")) {
                String type = content.path("type").asString(null);
                if ("refusal".equals(type)) {
                    refused = true;
                } else if ("output_text".equals(type) && content.path("text").isString()) {
                    text.append(content.path("text").asString());
                }
            }
        }
        String status = root.path("status").asString(null);
        if (refused) {
            throw new QuizJudgeException(Failure.REFUSAL, execution(model, exchanges));
        }
        if ("incomplete".equals(status)) {
            String reason = root.path("incomplete_details").path("reason").asString(null);
            log.warn("Quiz Judge 응답이 완료되지 않았습니다. reason={}",
                    reason != null && INCOMPLETE_REASONS.contains(reason) ? reason : "other");
            throw new QuizJudgeException(Failure.INCOMPLETE, execution(model, exchanges));
        }
        if ("failed".equals(status)) {
            log.warn("Quiz Judge 응답이 실패 상태입니다.");
            throw new QuizJudgeException(Failure.PROVIDER_ERROR, execution(model, exchanges));
        }
        if (!"completed".equals(status) || text.isEmpty()) {
            throw new QuizJudgeException(Failure.INVALID_RESPONSE, execution(model, exchanges));
        }
        return text.toString();
    }

    private Map<String, Object> requestBody(QuizJudgeInput input, QuizJudgeSettings settings) {
        Map<String, Object> format = new LinkedHashMap<>();
        format.put("type", "json_schema");
        format.put("name", QuizJudgePrompt.SCHEMA_NAME);
        format.put("schema", QuizJudgePrompt.responseSchema());
        format.put("strict", true);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", settings.model());
        body.put("instructions", QuizJudgePrompt.instructions(settings.reasonMaxLength()));
        body.put("input", QuizJudgePrompt.input(input, objectMapper));
        body.put("reasoning", Map.of("effort", settings.reasoningEffort()));
        body.put("max_output_tokens", settings.maxOutputTokens());
        body.put("text", Map.of("format", format));
        body.put("store", false);
        return body;
    }

    private JsonNode readResponse(String responseBody) {
        try {
            JsonNode root = objectMapper.readTree(responseBody == null ? "" : responseBody);
            return root != null && root.isObject() ? root : null;
        } catch (JacksonException exception) {
            return null;
        }
    }

    private JudgeUsage usage(JsonNode root) {
        JsonNode usage = root.path("usage");
        if (!usage.isObject()) {
            return null;
        }
        return new JudgeUsage(count(usage.path("input_tokens")),
                count(usage.path("input_tokens_details").path("cached_tokens")),
                count(usage.path("output_tokens")),
                count(usage.path("output_tokens_details").path("reasoning_tokens")),
                count(usage.path("total_tokens")));
    }

    private Long count(JsonNode node) {
        return node.isIntegralNumber() && node.canConvertToLong() && node.longValue() >= 0 ? node.longValue() : null;
    }

    private String text(JsonNode root, String field) {
        return root.path(field).isString() ? root.path(field).asString() : null;
    }

    /**
     * 재시도 전 대기. 유효한 Retry-After(초 또는 HTTP 날짜)가 상한 이하면 그 값, 상한을 넘으면 재시도하지 않는다(빈 값).
     * 없거나 잘못된 값이면 기본 대기다.
     */
    private Optional<Duration> retryWait(HttpHeaders headers) {
        String value = headers == null ? null : headers.getFirst(HttpHeaders.RETRY_AFTER);
        Duration delay = value == null ? null : parseRetryAfter(value.trim());
        if (delay == null) {
            return Optional.of(properties.getHttp().getRetryBackoff());
        }
        return delay.compareTo(properties.getHttp().getRetryAfterMax()) <= 0 ? Optional.of(delay) : Optional.empty();
    }

    private Duration parseRetryAfter(String value) {
        if (DELAY_SECONDS.matcher(value).matches()) {
            // 아주 큰 값도 유효한 초이므로 상한을 넘는 대기로 본다.
            return value.length() > 9 ? ChronoUnit.FOREVER.getDuration() : Duration.ofSeconds(Long.parseLong(value));
        }
        try {
            Duration delay = Duration.between(clock.instant(),
                    ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant());
            return delay.isNegative() ? Duration.ZERO : delay;
        } catch (DateTimeParseException exception) {
            return null;
        }
    }

    private void pause(Duration duration, String model, List<QuizJudgeExecution.Exchange> exchanges) {
        try {
            sleeper.sleep(duration);
        } catch (InterruptedException exception) {
            throw interrupted(QuizJudgeInterruptedException.Phase.RETRY_WAIT, model, exchanges);
        }
    }

    /** interrupt 상태를 되살리고 중단 예외를 만든다. 호출자는 바로 던지고 HTTP 요청을 더 보내지 않는다. */
    private QuizJudgeInterruptedException interrupted(QuizJudgeInterruptedException.Phase phase, String model,
                                                      List<QuizJudgeExecution.Exchange> exchanges) {
        Thread.currentThread().interrupt();
        log.warn("Quiz Judge 실행이 중단되었습니다. phase={}", phase);
        return new QuizJudgeInterruptedException(phase, execution(model, exchanges));
    }

    /**
     * 단가가 설정돼 있으면 비용을 추정한다. 요청마다 input·cached input·output token이 모두 있고 cached가 input을 넘지
     * 않아야 계산한다. 빠지거나 모순된 값은 0으로 채우거나 보정하지 않고 그 요청을 계산에서 빼며, 하나라도 빠지면
     * 불완전으로 표시한다. 계산할 수 있는 요청이 없으면 금액은 null이다.
     */
    private QuizJudgeExecution execution(String model, List<QuizJudgeExecution.Exchange> exchanges) {
        OpenAiJudgeProperties.Pricing pricing = properties.getPricing();
        if (pricing == null || !pricing.isConfigured()) {
            return new QuizJudgeExecution(model, exchanges, null);
        }
        BigDecimal usd = null;
        boolean complete = true;
        for (QuizJudgeExecution.Exchange exchange : exchanges) {
            JudgeUsage usage = exchange.usage();
            if (usage == null || !usage.isCostComputable()) {
                complete = false;
                continue;
            }
            long cached = usage.cachedInputTokens();
            BigDecimal cost = price(pricing.getInputUsdPerMillionTokens(), usage.inputTokens() - cached)
                    .add(price(pricing.getCachedInputUsdPerMillionTokens(), cached))
                    .add(price(pricing.getOutputUsdPerMillionTokens(), usage.outputTokens()))
                    .divide(ONE_MILLION, MathContext.DECIMAL64);
            usd = usd == null ? cost : usd.add(cost);
        }
        return new QuizJudgeExecution(model, exchanges,
                new QuizJudgeExecution.CostEstimate(usd, pricing.getVersion(), complete && usd != null));
    }

    private static BigDecimal price(BigDecimal usdPerMillionTokens, long tokens) {
        return usdPerMillionTokens.multiply(BigDecimal.valueOf(tokens));
    }

    private static Duration min(Duration first, Duration second) {
        return first.compareTo(second) <= 0 ? first : second;
    }

    /** 중단으로 끊긴 I/O. {@link SocketTimeoutException}도 {@link InterruptedIOException}이라 시간 초과는 제외한다. */
    private boolean isInterruption(Throwable throwable) {
        for (Throwable current = throwable; current != null; current = current.getCause()) {
            if (current instanceof InterruptedException
                    || current instanceof ClosedByInterruptException
                    || (current instanceof InterruptedIOException && !(current instanceof SocketTimeoutException))) {
                return true;
            }
        }
        return false;
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

    /** 요청마다 연결·응답 대기 시간을 정해 Client를 만든다. 테스트에서 Mock 서버에 연결된 Client로 바꿀 수 있다. */
    @FunctionalInterface
    interface ClientFactory {
        RestClient create(Duration connectTimeout, Duration readTimeout);
    }

    /** 재시도 대기. 테스트에서 실제로 기다리지 않도록 바꿀 수 있다. */
    @FunctionalInterface
    interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;
    }
}
