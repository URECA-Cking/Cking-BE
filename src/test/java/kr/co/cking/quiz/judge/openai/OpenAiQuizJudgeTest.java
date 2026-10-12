package kr.co.cking.quiz.judge.openai;

import kr.co.cking.quiz.judge.JudgeAggregation;
import kr.co.cking.quiz.judge.JudgeUsage;
import kr.co.cking.quiz.judge.QuizJudgeException;
import kr.co.cking.quiz.judge.QuizJudgeException.Failure;
import kr.co.cking.quiz.judge.QuizJudgeExecution;
import kr.co.cking.quiz.judge.QuizJudgeFingerprint;
import kr.co.cking.quiz.judge.QuizJudgeFixtures;
import kr.co.cking.quiz.judge.QuizJudgeInput;
import kr.co.cking.quiz.judge.QuizJudgeInterruptedException;
import kr.co.cking.quiz.judge.QuizJudgePrompt;
import kr.co.cking.quiz.judge.QuizJudgeResult;
import kr.co.cking.quiz.judge.QuizJudgeTimeoutException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ResponseCreator;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.InterruptedIOException;
import java.math.BigDecimal;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.nio.channels.ClosedByInterruptException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

@ExtendWith(OutputCaptureExtension.class)
class OpenAiQuizJudgeTest {

    private static final String URL = "https://openai.test/v1/responses";
    private static final String API_KEY = "test-openai-key-5d1e";
    private static final String PROVIDER_SECRET = "provider-raw-detail-83a7";
    private static final Instant NOW = Instant.parse("2026-10-12T00:00:00Z");

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RestClient.Builder builder = RestClient.builder().baseUrl("https://openai.test/v1");
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final RestClient client = builder.build();
    private final AtomicLong nanos = new AtomicLong();
    private final List<Duration> sleeps = new ArrayList<>();
    private final List<Duration[]> timeouts = new ArrayList<>();
    private final AtomicReference<String> requestBody = new AtomicReference<>();
    private OpenAiJudgeProperties properties;

    @BeforeEach
    void setUp() {
        properties = new OpenAiJudgeProperties();
        properties.setApiKey(API_KEY);
        properties.setBaseUrl("https://openai.test/v1");
    }

    @Test
    void Responses_API를_strict_schema로_호출하고_검증한_평가와_해시를_돌려준다() throws Exception {
        QuizJudgeInput input = QuizJudgeFixtures.input(3);
        server.expect(requestTo(URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + API_KEY))
                .andExpect(request -> requestBody.set(((MockClientHttpRequest) request).getBodyAsString()))
                .andRespond(withSuccess(completed(QuizJudgeFixtures.allPass(3)), MediaType.APPLICATION_JSON));

        QuizJudgeResult result = judge().judge(input);

        server.verify();
        JsonNode body = objectMapper.readTree(requestBody.get());
        assertThat(body.propertyNames()).containsExactlyInAnyOrder("model", "instructions", "input", "reasoning",
                "max_output_tokens", "text", "store");
        assertThat(body.path("model").asString()).isEqualTo("gpt-6.1-sol");
        assertThat(body.path("instructions").asString()).isEqualTo(QuizJudgePrompt.instructions(200));
        assertThat(body.path("input").asString()).isEqualTo(QuizJudgePrompt.input(input, objectMapper));
        assertThat(body.path("reasoning").path("effort").asString()).isEqualTo("medium");
        assertThat(body.path("max_output_tokens").asInt()).isEqualTo(8192);
        JsonNode format = body.path("text").path("format");
        assertThat(format.path("type").asString()).isEqualTo("json_schema");
        assertThat(format.path("name").asString()).isEqualTo(QuizJudgePrompt.SCHEMA_NAME);
        assertThat(format.path("strict").asBoolean()).isTrue();
        assertThat(objectMapper.writeValueAsString(format.path("schema")))
                .isEqualTo(objectMapper.writeValueAsString(QuizJudgePrompt.responseSchema()));
        assertThat(body.path("store").asBoolean(true)).isFalse();

        assertThat(result.evaluation().aggregation()).isEqualTo(JudgeAggregation.ALL_PASS);
        assertThat(result.evaluation().questions()).hasSize(3);
        assertThat(result.settings().model()).isEqualTo("gpt-6.1-sol");
        assertThat(result.settings().maxOutputTokens()).isEqualTo(8192);
        assertThat(result.inputSha256()).isEqualTo(QuizJudgeFingerprint.inputSha256(input));
        assertThat(result.settingsFingerprint()).isEqualTo(QuizJudgeFingerprint.settingsFingerprint(result.settings()));
        assertThat(result.resultSha256()).isEqualTo(QuizJudgeFingerprint.resultSha256(result.inputSha256(),
                result.settingsFingerprint(), QuizJudgePrompt.POLICY_VERSION, result.evaluation()));
        QuizJudgeExecution execution = result.execution();
        assertThat(execution.requestedModel()).isEqualTo("gpt-6.1-sol");
        assertThat(execution.responseModel()).isEqualTo("gpt-6.1-sol-2026-09-01");
        assertThat(execution.httpRequestCount()).isEqualTo(1);
        assertThat(execution.exchanges().get(0).responseId()).isEqualTo("resp_1");
        assertThat(execution.exchanges().get(0).usage()).isEqualTo(new JudgeUsage(1000L, 200L, 500L, 300L, 1500L));
        assertThat(execution.usageComplete()).isTrue();
        assertThat(execution.cost()).isNull();
        assertThat(timeouts).singleElement().satisfies(pair ->
                assertThat(pair).containsExactly(Duration.ofSeconds(2), Duration.ofSeconds(60)));
        assertThat(sleeps).isEmpty();
    }

    @Test
    void 출력_token_한도는_문항_수로_정하고_한도가_없는_문항_수는_호출하지_않는다() throws Exception {
        server.expect(requestTo(URL))
                .andExpect(request -> requestBody.set(((MockClientHttpRequest) request).getBodyAsString()))
                .andRespond(withSuccess(completed(QuizJudgeFixtures.allPass(5)), MediaType.APPLICATION_JSON));

        assertThat(judge().judge(QuizJudgeFixtures.input(5)).settings().maxOutputTokens()).isEqualTo(12288);
        assertThat(objectMapper.readTree(requestBody.get()).path("max_output_tokens").asInt()).isEqualTo(12288);

        assertThatThrownBy(() -> judge().judge(QuizJudgeFixtures.input(4))).isInstanceOf(IllegalArgumentException.class);
        server.verify();
    }

    @Test
    void API_key가_없으면_호출하지_않는다() {
        properties.setApiKey(" ");

        assertFailure(() -> judge().judge(QuizJudgeFixtures.input(3)), Failure.NOT_CONFIGURED, 0);
        server.verify();
    }

    @Test
    void HTTP_429와_5xx는_Retry_After만큼_기다려_한_번만_재시도한다() {
        String retryAt = DateTimeFormatter.RFC_1123_DATE_TIME.format(NOW.plusSeconds(2).atZone(ZoneOffset.UTC));
        Object[][] cases = {
                {HttpStatus.TOO_MANY_REQUESTS, "3", Duration.ofSeconds(3)},
                {HttpStatus.SERVICE_UNAVAILABLE, "0", Duration.ZERO},
                {HttpStatus.TOO_MANY_REQUESTS, "5", Duration.ofSeconds(5)},
                {HttpStatus.BAD_GATEWAY, retryAt, Duration.ofSeconds(2)},
                {HttpStatus.TOO_MANY_REQUESTS, null, Duration.ofSeconds(1)},
                {HttpStatus.INTERNAL_SERVER_ERROR, "soon", Duration.ofSeconds(1)},
                {HttpStatus.TOO_MANY_REQUESTS, "-1", Duration.ofSeconds(1)},
                {HttpStatus.TOO_MANY_REQUESTS, "1.5", Duration.ofSeconds(1)}};
        for (Object[] value : cases) {
            reset();
            ResponseCreator failure = withStatus((HttpStatus) value[0]);
            if (value[1] != null) {
                failure = withStatus((HttpStatus) value[0]).header(HttpHeaders.RETRY_AFTER, (String) value[1]);
            }
            server.expect(requestTo(URL)).andRespond(failure);
            server.expect(requestTo(URL))
                    .andRespond(withSuccess(completed(QuizJudgeFixtures.allPass(3)), MediaType.APPLICATION_JSON));

            QuizJudgeResult result = judge().judge(QuizJudgeFixtures.input(3));

            server.verify();
            assertThat(sleeps).as(String.valueOf(value[1])).containsExactly((Duration) value[2]);
            assertThat(result.execution().httpRequestCount()).isEqualTo(2);
            assertThat(result.execution().exchanges().get(0).httpStatus()).isEqualTo(((HttpStatus) value[0]).value());
            assertThat(result.execution().usageComplete()).isFalse();
        }
    }

    @Test
    void Retry_After가_상한을_넘거나_두_번째도_실패하면_재시도하지_않는다() {
        for (String retryAfter : new String[] {"6", "99999999999999999999"}) {
            reset();
            server.expect(ExpectedCount.once(), requestTo(URL))
                    .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).header(HttpHeaders.RETRY_AFTER, retryAfter));
            assertFailure(() -> judge().judge(QuizJudgeFixtures.input(3)), Failure.PROVIDER_ERROR, 1);
            server.verify();
            assertThat(sleeps).isEmpty();
        }

        reset();
        server.expect(ExpectedCount.times(2), requestTo(URL)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        assertFailure(() -> judge().judge(QuizJudgeFixtures.input(3)), Failure.PROVIDER_ERROR, 2);
        server.verify();
        assertThat(sleeps).containsExactly(Duration.ofSeconds(1));

        reset();
        properties.getHttp().setMaxAttempts(1);
        server.expect(ExpectedCount.once(), requestTo(URL)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        assertFailure(() -> judge().judge(QuizJudgeFixtures.input(3)), Failure.PROVIDER_ERROR, 1);
        server.verify();
        assertThat(sleeps).isEmpty();
    }

    @Test
    void HTTP_429가_아닌_4xx는_재시도하지_않는다() {
        server.expect(ExpectedCount.once(), requestTo(URL)).andRespond(withStatus(HttpStatus.BAD_REQUEST));

        assertFailure(() -> judge().judge(QuizJudgeFixtures.input(3)), Failure.REJECTED, 1);
        server.verify();
        assertThat(sleeps).isEmpty();
    }

    @Test
    void 응답_대기_시간_초과는_재시도하지_않고_결과를_알_수_없는_시간_초과다() {
        server.expect(ExpectedCount.once(), requestTo(URL))
                .andRespond(withException(new SocketTimeoutException("Read timed out " + PROVIDER_SECRET)));

        assertThatThrownBy(() -> judge().judge(QuizJudgeFixtures.input(3)))
                .isInstanceOfSatisfying(QuizJudgeTimeoutException.class, exception -> {
                    assertThat(exception.isExecutionDeadlineExceeded()).isFalse();
                    assertThat(exception).hasNoCause();
                    assertThat(exception.getMessage()).doesNotContain(PROVIDER_SECRET);
                    assertThat(exception.getExecution().httpRequestCount()).isEqualTo(1);
                    assertThat(exception.getExecution().exchanges().get(0).httpStatus()).isNull();
                });
        server.verify();
        assertThat(sleeps).isEmpty();
    }

    @Test
    void 재시도_요청의_대기_시간은_남은_전체_실행_시간을_넘지_않는다() {
        server.expect(requestTo(URL)).andRespond(advanceThen(Duration.ofSeconds(147),
                withStatus(HttpStatus.TOO_MANY_REQUESTS)));
        server.expect(requestTo(URL)).andRespond(withException(new SocketTimeoutException("Read timed out")));

        assertThatThrownBy(() -> judge().judge(QuizJudgeFixtures.input(3)))
                .isInstanceOfSatisfying(QuizJudgeTimeoutException.class, exception ->
                        assertThat(exception.isExecutionDeadlineExceeded()).isTrue());
        server.verify();
        assertThat(sleeps).containsExactly(Duration.ofSeconds(1));
        assertThat(timeouts.get(1)).containsExactly(Duration.ofSeconds(2), Duration.ofSeconds(2));
    }

    @Test
    void 재시도_대기_뒤_남은_실행_시간이_없으면_다시_요청하지_않는다() {
        server.expect(ExpectedCount.once(), requestTo(URL)).andRespond(advanceThen(Duration.ofSeconds(149),
                withStatus(HttpStatus.SERVICE_UNAVAILABLE)));

        assertFailure(() -> judge().judge(QuizJudgeFixtures.input(3)), Failure.PROVIDER_ERROR, 1);
        server.verify();
        assertThat(sleeps).isEmpty();
    }

    @Test
    void 전체_실행_시간을_넘겨_받은_응답은_유효해도_쓰지_않는다() {
        server.expect(requestTo(URL)).andRespond(advanceThen(Duration.ofSeconds(151),
                withSuccess(completed(QuizJudgeFixtures.allPass(3)), MediaType.APPLICATION_JSON)));

        assertThatThrownBy(() -> judge().judge(QuizJudgeFixtures.input(3)))
                .isInstanceOfSatisfying(QuizJudgeTimeoutException.class, exception -> {
                    assertThat(exception.isExecutionDeadlineExceeded()).isTrue();
                    assertThat(exception.getExecution().exchanges().get(0).usage().outputTokens()).isEqualTo(500L);
                });
    }

    @Test
    void refusal_incomplete_failed_형식_오류는_서로_구분하고_재시도하지_않는다() {
        String refusal = response("completed", "[{\"type\": \"message\", \"content\": ["
                + "{\"type\": \"refusal\", \"refusal\": \"" + PROVIDER_SECRET + "\"}]}]");
        String incomplete = response("incomplete", "[]")
                .replace("\"status\"", "\"incomplete_details\": {\"reason\": \"max_output_tokens\"}, \"status\"");
        Object[][] cases = {
                {refusal, Failure.REFUSAL},
                {incomplete, Failure.INCOMPLETE},
                {response("failed", "[]"), Failure.PROVIDER_ERROR},
                {response("in_progress", "[]"), Failure.INVALID_RESPONSE},
                {response("completed", "[]"), Failure.INVALID_RESPONSE},
                {response("completed", "[{\"type\": \"reasoning\", \"summary\": []}]"), Failure.INVALID_RESPONSE},
                {completed("not json"), Failure.INVALID_RESPONSE},
                {completed(QuizJudgeFixtures.allPass(2)), Failure.INVALID_RESPONSE},
                {completed(QuizJudgeFixtures.output(3, (no, rubric) -> new String[] {"pass", " "})),
                        Failure.INVALID_RESPONSE},
                {"not json", Failure.INVALID_RESPONSE},
                {"[]", Failure.INVALID_RESPONSE}};
        for (Object[] value : cases) {
            reset();
            server.expect(ExpectedCount.once(), requestTo(URL))
                    .andRespond(withSuccess((String) value[0], MediaType.APPLICATION_JSON));

            assertFailure(() -> judge().judge(QuizJudgeFixtures.input(3)), (Failure) value[1], 1);
            server.verify();
            assertThat(sleeps).isEmpty();
        }
    }

    @Test
    void 평가를_거부하거나_끝나지_않은_응답도_관측한_사용량을_남긴다() {
        String incomplete = response("incomplete", "[]");
        server.expect(requestTo(URL)).andRespond(withSuccess(incomplete, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> judge().judge(QuizJudgeFixtures.input(3)))
                .isInstanceOfSatisfying(QuizJudgeException.class, exception -> {
                    assertThat(exception.getExecution().exchanges().get(0).usage().totalTokens()).isEqualTo(1500L);
                    assertThat(exception.getExecution().responseModel()).isEqualTo("gpt-6.1-sol-2026-09-01");
                });
    }

    @Test
    void 단가를_설정하면_관측한_사용량으로_비용을_추정하고_빠진_요청이_있으면_불완전으로_표시한다() {
        setPricing();
        server.expect(requestTo(URL))
                .andRespond(withSuccess(completed(QuizJudgeFixtures.allPass(3)), MediaType.APPLICATION_JSON));

        QuizJudgeExecution.CostEstimate cost = judge().judge(QuizJudgeFixtures.input(3)).execution().cost();

        // (800 × 2 + 200 × 0.5 + 500 × 10) / 1,000,000. reasoning token은 output에 포함돼 다시 더하지 않는다.
        assertThat(cost.usd()).isEqualByComparingTo("0.0067");
        assertThat(cost.pricingVersion()).isEqualTo("pricing-test-v1");
        assertThat(cost.complete()).isTrue();

        reset();
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
        server.expect(requestTo(URL))
                .andRespond(withSuccess(completed(QuizJudgeFixtures.allPass(3)), MediaType.APPLICATION_JSON));
        QuizJudgeExecution.CostEstimate partial = judge().judge(QuizJudgeFixtures.input(3)).execution().cost();
        assertThat(partial.usd()).isEqualByComparingTo("0.0067");
        assertThat(partial.complete()).isFalse();

        reset();
        server.expect(ExpectedCount.once(), requestTo(URL))
                .andRespond(withException(new SocketTimeoutException("Read timed out")));
        assertThatThrownBy(() -> judge().judge(QuizJudgeFixtures.input(3)))
                .isInstanceOfSatisfying(QuizJudgeTimeoutException.class, exception -> {
                    assertThat(exception.getExecution().cost().usd()).isNull();
                    assertThat(exception.getExecution().cost().complete()).isFalse();
                });
    }

    @Test
    void cached_token이_없거나_input보다_크면_0으로_채우거나_보정하지_않고_비용을_미확인으로_둔다() {
        setPricing();
        String missingCached = "{\"input_tokens\": 1000, \"output_tokens\": 500, "
                + "\"output_tokens_details\": {\"reasoning_tokens\": 300}, \"total_tokens\": 1500}";
        String cachedOverInput = "{\"input_tokens\": 100, \"input_tokens_details\": {\"cached_tokens\": 200}, "
                + "\"output_tokens\": 500, \"total_tokens\": 600}";
        String missingOutput = "{\"input_tokens\": 1000, \"input_tokens_details\": {\"cached_tokens\": 0}}";
        for (String usage : new String[] {missingCached, cachedOverInput, missingOutput, "null"}) {
            reset();
            server.expect(requestTo(URL)).andRespond(withSuccess(
                    response("completed", messageOutput(QuizJudgeFixtures.allPass(3)), usage),
                    MediaType.APPLICATION_JSON));

            QuizJudgeExecution execution = judge().judge(QuizJudgeFixtures.input(3)).execution();

            assertThat(execution.cost().usd()).as(usage).isNull();
            assertThat(execution.cost().complete()).as(usage).isFalse();
        }
        assertThat(new JudgeUsage(1000L, null, 500L, 300L, 1500L).isCostComputable()).isFalse();
        assertThat(new JudgeUsage(100L, 200L, 500L, null, 600L).isCostComputable()).isFalse();
    }

    @Test
    void cached_token이_0이면_정상값으로_계산하고_reasoning_token은_더하지_않는다() {
        setPricing();
        String usage = "{\"input_tokens\": 1000, \"input_tokens_details\": {\"cached_tokens\": 0}, "
                + "\"output_tokens\": 500, \"output_tokens_details\": {\"reasoning_tokens\": 400}, "
                + "\"total_tokens\": 1500}";
        server.expect(requestTo(URL)).andRespond(withSuccess(
                response("completed", messageOutput(QuizJudgeFixtures.allPass(3)), usage), MediaType.APPLICATION_JSON));

        QuizJudgeExecution.CostEstimate cost = judge().judge(QuizJudgeFixtures.input(3)).execution().cost();

        // (1000 × 2 + 0 × 0.5 + 500 × 10) / 1,000,000
        assertThat(cost.usd()).isEqualByComparingTo("0.007");
        assertThat(cost.complete()).isTrue();
    }

    @Test
    void 재시도_중_한_응답의_사용량이_빠지면_계산한_부분만_남기고_불완전으로_표시한다() {
        setPricing();
        String missingCached = "{\"input_tokens\": 1000, \"output_tokens\": 500, \"total_tokens\": 1500}";
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        server.expect(requestTo(URL)).andRespond(withSuccess(
                response("completed", messageOutput(QuizJudgeFixtures.allPass(3)), missingCached),
                MediaType.APPLICATION_JSON));

        QuizJudgeExecution execution = judge().judge(QuizJudgeFixtures.input(3)).execution();

        assertThat(execution.usageComplete()).isFalse();
        assertThat(execution.cost().usd()).isNull();
        assertThat(execution.cost().complete()).isFalse();
    }

    @Test
    void 재시도_대기_중_중단되면_다음_요청_없이_중단으로_끝나고_interrupt_상태를_유지한다() {
        server.expect(ExpectedCount.once(), requestTo(URL)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
        OpenAiQuizJudge judge = new OpenAiQuizJudge(properties, objectMapper, (connect, read) -> client,
                nanos::get, duration -> {
                    throw new InterruptedException();
                }, Clock.fixed(NOW, ZoneOffset.UTC));

        try {
            assertInterrupted(() -> judge.judge(QuizJudgeFixtures.input(3)),
                    QuizJudgeInterruptedException.Phase.RETRY_WAIT, 1);
            server.verify();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void HTTP_요청_중_중단은_시간_초과나_네트워크_오류가_아닌_중단이다() {
        ResponseCreator[] interruptions = {
                withException(new InterruptedIOException("interrupted " + PROVIDER_SECRET)),
                withException(new ClosedByInterruptException()),
                request -> {
                    // 중단된 thread에서 소켓이 시간 초과로 끊겨도 중단으로 본다.
                    Thread.currentThread().interrupt();
                    throw new SocketTimeoutException("Read timed out");
                },
                request -> {
                    Thread.currentThread().interrupt();
                    throw new ConnectException("Connection refused");
                }};
        for (ResponseCreator interruption : interruptions) {
            reset();
            server.expect(ExpectedCount.once(), requestTo(URL)).andRespond(interruption);
            try {
                assertInterrupted(() -> judge().judge(QuizJudgeFixtures.input(3)),
                        QuizJudgeInterruptedException.Phase.HTTP_REQUEST, 1);
                server.verify();
                assertThat(sleeps).isEmpty();
            } finally {
                Thread.interrupted();
            }
        }
    }

    @Test
    void 응답을_받은_직후_중단을_확인하면_결과를_쓰지_않고_관측한_사용량을_남긴다() {
        server.expect(ExpectedCount.once(), requestTo(URL)).andRespond(request -> {
            Thread.currentThread().interrupt();
            return withSuccess(completed(QuizJudgeFixtures.allPass(3)), MediaType.APPLICATION_JSON)
                    .createResponse(request);
        });

        try {
            assertThatThrownBy(() -> judge().judge(QuizJudgeFixtures.input(3)))
                    .isInstanceOfSatisfying(QuizJudgeInterruptedException.class, exception ->
                            assertThat(exception.getExecution().exchanges().get(0).usage().totalTokens())
                                    .isEqualTo(1500L));
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void 이미_중단된_thread에서는_HTTP_요청을_보내지_않는다() {
        Thread.currentThread().interrupt();
        try {
            assertInterrupted(() -> judge().judge(QuizJudgeFixtures.input(3)),
                    QuizJudgeInterruptedException.Phase.HTTP_REQUEST, 0);
            server.verify();
            assertThat(timeouts).isEmpty();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void 응답은_제때_받았어도_후처리_중_전체_실행_시간을_넘기면_성공으로_돌려주지_않는다() {
        // nanoTime 호출 순서: 시작, 요청 직전 남은 시간, 응답 직후 검사, 반환 직전 검사.
        long[] readings = {0, 0, Duration.ofSeconds(149).toNanos(), Duration.ofSeconds(151).toNanos()};
        AtomicInteger calls = new AtomicInteger();
        server.expect(ExpectedCount.once(), requestTo(URL))
                .andRespond(withSuccess(completed(QuizJudgeFixtures.allPass(3)), MediaType.APPLICATION_JSON));
        OpenAiQuizJudge judge = new OpenAiQuizJudge(properties, objectMapper, (connect, read) -> client,
                () -> readings[Math.min(calls.getAndIncrement(), readings.length - 1)], sleeps::add,
                Clock.fixed(NOW, ZoneOffset.UTC));

        assertThatThrownBy(() -> judge.judge(QuizJudgeFixtures.input(3)))
                .isInstanceOfSatisfying(QuizJudgeTimeoutException.class, exception -> {
                    assertThat(exception.isExecutionDeadlineExceeded()).isTrue();
                    assertThat(exception).hasNoCause();
                    assertThat(exception.getExecution().httpRequestCount()).isEqualTo(1);
                    assertThat(exception.getExecution().exchanges().get(0).usage().outputTokens()).isEqualTo(500L);
                });
        assertThat(calls.get()).isEqualTo(4);
        server.verify();
    }

    @Test
    void 지원하지_않는_reasoning_effort로는_호출하지_않는다() {
        properties.setReasoningEffort("medum");

        assertFailure(() -> judge().judge(QuizJudgeFixtures.input(3)), Failure.NOT_CONFIGURED, 0);
        server.verify();
    }

    @Test
    void API_key와_Provider_원문과_평가_입력은_예외와_로그에_남지_않는다(CapturedOutput output) {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.FORBIDDEN)
                .body("{\"error\": \"" + PROVIDER_SECRET + " " + API_KEY + "\"}")
                .contentType(MediaType.APPLICATION_JSON));
        assertFailure(() -> judge().judge(QuizJudgeFixtures.input(3)), Failure.REJECTED, 1);

        reset();
        server.expect(requestTo(URL)).andRespond(withSuccess(
                completed("{\"questions\": \"" + PROVIDER_SECRET + "\"}"), MediaType.APPLICATION_JSON));
        assertFailure(() -> judge().judge(QuizJudgeFixtures.input(3)), Failure.INVALID_RESPONSE, 1);

        assertThat(output.getAll()).doesNotContain(PROVIDER_SECRET, API_KEY, "물은 증발해", "질문 0");
    }

    private OpenAiQuizJudge judge() {
        return new OpenAiQuizJudge(properties, objectMapper, (connect, read) -> {
            timeouts.add(new Duration[] {connect, read});
            return client;
        }, nanos::get, duration -> {
            sleeps.add(duration);
            nanos.addAndGet(duration.toNanos());
        }, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private void reset() {
        server.reset();
        sleeps.clear();
        timeouts.clear();
        nanos.set(0);
    }

    private ResponseCreator advanceThen(Duration elapsed, ResponseCreator creator) {
        return request -> {
            nanos.addAndGet(elapsed.toNanos());
            return creator.createResponse(request);
        };
    }

    private String completed(String outputText) {
        return response("completed", messageOutput(outputText));
    }

    private String messageOutput(String outputText) {
        return "[{\"type\": \"reasoning\", \"summary\": []}, "
                + "{\"type\": \"message\", \"role\": \"assistant\", \"content\": ["
                + "{\"type\": \"output_text\", \"text\": " + objectMapper.writeValueAsString(outputText) + "}]}]";
    }

    private String response(String status, String output) {
        return response(status, output, """
                {"input_tokens": 1000, "input_tokens_details": {"cached_tokens": 200},
                 "output_tokens": 500, "output_tokens_details": {"reasoning_tokens": 300}, "total_tokens": 1500}""");
    }

    private String response(String status, String output, String usage) {
        return """
                {"id": "resp_1", "object": "response", "status": "%s", "model": "gpt-6.1-sol-2026-09-01",
                 "output": %s, "usage": %s}
                """.formatted(status, output, usage);
    }

    private void setPricing() {
        properties.getPricing().setVersion("pricing-test-v1");
        properties.getPricing().setInputUsdPerMillionTokens(new BigDecimal("2"));
        properties.getPricing().setCachedInputUsdPerMillionTokens(new BigDecimal("0.5"));
        properties.getPricing().setOutputUsdPerMillionTokens(new BigDecimal("10"));
    }

    private void assertInterrupted(org.assertj.core.api.ThrowableAssert.ThrowingCallable call,
                                   QuizJudgeInterruptedException.Phase phase, int httpRequests) {
        assertThatThrownBy(call).isInstanceOfSatisfying(QuizJudgeInterruptedException.class, exception -> {
            assertThat(exception.getPhase()).isEqualTo(phase);
            assertThat(exception).hasNoCause();
            assertThat(exception.getMessage()).doesNotContain(PROVIDER_SECRET, API_KEY);
            assertThat(exception.getExecution().httpRequestCount()).isEqualTo(httpRequests);
        });
        assertThat(Thread.currentThread().isInterrupted()).isTrue();
    }

    private void assertFailure(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, Failure failure,
                               int httpRequests) {
        assertThatThrownBy(call).isInstanceOfSatisfying(QuizJudgeException.class, exception -> {
            assertThat(exception.getFailure()).isEqualTo(failure);
            assertThat(exception).hasNoCause();
            assertThat(exception.getMessage()).doesNotContain(PROVIDER_SECRET, API_KEY);
            assertThat(exception.getExecution().httpRequestCount()).isEqualTo(httpRequests);
            assertThat(exception.getExecution().requestedModel()).isEqualTo("gpt-6.1-sol");
        });
    }
}
