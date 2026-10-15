package kr.co.cking.quiz.grounding.gemini;

import kr.co.cking.quiz.generation.QuizProviderTimeoutException;
import kr.co.cking.quiz.grounding.EvidenceType;
import kr.co.cking.quiz.grounding.GroundingFact;
import kr.co.cking.quiz.grounding.GroundingPrompt;
import kr.co.cking.quiz.grounding.VideoGrounding;
import kr.co.cking.quiz.grounding.VideoGroundingException;
import kr.co.cking.quiz.grounding.VideoGroundingException.Failure;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
class GeminiVideoGrounderTest {

    private static final String URL = "https://gemini.test/v1beta/interactions";
    private static final String API_KEY = "test-key-7f3a";
    private static final String VIDEO_ID = "dQw4w9WgXcQ";
    private static final String PROVIDER_SECRET = "provider-raw-detail-91c2";
    private static final String OUTPUT = """
            {"contentText": "물은 증발해 구름이 된다.",
             "facts": [{"fact": "물은 증발한다.", "evidenceType": "SPEECH", "evidence": "물이 증발합니다",
                        "timestampStart": "00:10", "timestampEnd": "00:12.5"}]}""";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RestClient.Builder builder = RestClient.builder().baseUrl("https://gemini.test/v1beta");
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final List<Duration> sleeps = new ArrayList<>();
    private final AtomicReference<String> requestBody = new AtomicReference<>();
    private GeminiGroundingProperties properties;

    @BeforeEach
    void setUp() {
        properties = new GeminiGroundingProperties();
        properties.setApiKey(API_KEY);
        properties.setModel("gemini-test-model");
    }

    @Test
    void 영상_watch_URL과_prompt_schema로_Interactions를_호출하고_결과를_돌려준다() throws Exception {
        server.expect(requestTo(URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("x-goog-api-key", API_KEY))
                .andExpect(request -> requestBody.set(((MockClientHttpRequest) request).getBodyAsString()))
                .andRespond(withSuccess(completed(OUTPUT), MediaType.APPLICATION_JSON));

        VideoGrounding grounding = grounder().ground(VIDEO_ID);

        server.verify();
        assertThat(grounding.contentText()).isEqualTo("물은 증발해 구름이 된다.");
        assertThat(grounding.promptVersion()).isEqualTo("quiz-grounding-v1");
        assertThat(grounding.model()).isEqualTo("gemini-test-model");
        assertThat(grounding.facts()).containsExactly(
                new GroundingFact("물은 증발한다.", EvidenceType.SPEECH, "물이 증발합니다", "00:10", "00:12.5"));

        JsonNode body = objectMapper.readTree(requestBody.get());
        assertThat(body.path("model").asString()).isEqualTo("gemini-test-model");
        assertThat(body.path("input").get(0).path("type").asString()).isEqualTo("video");
        assertThat(body.path("input").get(0).path("uri").asString())
                .isEqualTo("https://www.youtube.com/watch?v=" + VIDEO_ID);
        assertThat(body.path("input").get(1).path("type").asString()).isEqualTo("text");
        assertThat(body.path("input").get(1).path("text").asString()).isEqualTo(GroundingPrompt.text());
        assertThat(body.path("response_format").path("type").asString()).isEqualTo("text");
        assertThat(body.path("response_format").path("mime_type").asString()).isEqualTo("application/json");
        assertThat(objectMapper.convertValue(body.path("response_format").path("schema"), Map.class))
                .isEqualTo(GroundingPrompt.responseSchema());
        assertThat(body.path("store").asBoolean(true)).isFalse();
        assertThat(body.has("generation_config")).isFalse();
    }

    @Test
    void 출력_token_상한을_설정하면_generation_config에_넣는다() throws Exception {
        properties.setMaxOutputTokens(4096);
        server.expect(requestTo(URL))
                .andExpect(request -> requestBody.set(((MockClientHttpRequest) request).getBodyAsString()))
                .andRespond(withSuccess(completed(OUTPUT), MediaType.APPLICATION_JSON));

        grounder().ground(VIDEO_ID);

        assertThat(objectMapper.readTree(requestBody.get()).path("generation_config").path("max_output_tokens").asInt())
                .isEqualTo(4096);
    }

    @Test
    void videoId_형식이_아니면_호출하지_않는다() {
        for (String videoId : new String[] {null, "short", "dQw4w9WgXcQ1", "dQw4w9WgXc!",
                "https://youtu.be/dQw4w9WgXcQ"}) {
            assertThatThrownBy(() -> grounder().ground(videoId)).isInstanceOf(IllegalArgumentException.class);
        }
        server.verify();
    }

    @Test
    void API_key가_없으면_호출하지_않는다() {
        properties.setApiKey(" ");

        assertFailure(() -> grounder().ground(VIDEO_ID), Failure.NOT_CONFIGURED);
        server.verify();
    }

    @Test
    void HTTP_429는_대기_후_재시도해_성공한다() {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
        server.expect(requestTo(URL)).andRespond(withSuccess(completed(OUTPUT), MediaType.APPLICATION_JSON));

        assertThat(grounder().ground(VIDEO_ID).contentText()).isEqualTo("물은 증발해 구름이 된다.");
        server.verify();
        assertThat(sleeps).containsExactly(Duration.ofSeconds(1));
    }

    @Test
    void HTTP_5xx가_계속되면_최대_시도_횟수에서_멈춘다() {
        server.expect(ExpectedCount.times(3), requestTo(URL)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        assertFailure(() -> grounder().ground(VIDEO_ID), Failure.PROVIDER_ERROR);
        server.verify();
        assertThat(sleeps).containsExactly(Duration.ofSeconds(1), Duration.ofSeconds(2));
    }

    @Test
    void 재시도_대기는_지수로_늘고_상한을_넘지_않는다() {
        properties.setMaxAttempts(5);
        properties.setRetryBackoffMax(Duration.ofSeconds(3));
        server.expect(ExpectedCount.times(5), requestTo(URL)).andRespond(withStatus(HttpStatus.BAD_GATEWAY));

        assertFailure(() -> grounder().ground(VIDEO_ID), Failure.PROVIDER_ERROR);
        assertThat(sleeps).containsExactly(Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(3),
                Duration.ofSeconds(3));
    }

    @Test
    void timeout은_재시도하지_않고_원인을_붙이지_않는다() {
        server.expect(ExpectedCount.once(), requestTo(URL))
                .andRespond(withException(new SocketTimeoutException("Read timed out " + PROVIDER_SECRET)));

        assertThatThrownBy(() -> grounder().ground(VIDEO_ID))
                .isInstanceOfSatisfying(QuizProviderTimeoutException.class, exception -> {
                    assertThat(exception).hasNoCause();
                    assertThat(exception.getMessage()).doesNotContain(PROVIDER_SECRET);
                });
        server.verify();
        assertThat(sleeps).isEmpty();
    }

    @Test
    void HTTP_429가_아닌_4xx는_재시도하지_않는다() {
        server.expect(ExpectedCount.once(), requestTo(URL)).andRespond(withStatus(HttpStatus.BAD_REQUEST));

        assertFailure(() -> grounder().ground(VIDEO_ID), Failure.REJECTED);
        server.verify();
        assertThat(sleeps).isEmpty();
    }

    @Test
    void 완료되지_않았거나_형식이_틀린_응답은_재시도하지_않는다() {
        String[] responses = {
                "{\"status\": \"incomplete\", \"steps\": []}",
                completed("not json"),
                completed("{\"contentText\": \" \", \"facts\": []}"),
                completed("{\"contentText\": \"본문\", \"facts\": [], \"model\": \"provider-model\"}"),
                completed("{\"contentText\": \"본문\", \"facts\": [{\"fact\": \"사실\", \"evidenceType\": \"SPEECH\", "
                        + "\"evidence\": \"근거\", \"timestampStart\": null}]}"),
                "{\"status\": \"completed\", \"steps\": [{\"type\": \"user_input\"}]}",
                "not json"
        };
        for (String response : responses) {
            server.reset();
            server.expect(ExpectedCount.once(), requestTo(URL))
                    .andRespond(withSuccess(response, MediaType.APPLICATION_JSON));

            assertFailure(() -> grounder().ground(VIDEO_ID), Failure.INVALID_RESPONSE);
            server.verify();
        }
        assertThat(sleeps).isEmpty();
    }

    @Test
    void model_output이_아닌_step과_text가_아닌_content는_무시한다() {
        String response = """
                {"status": "completed", "steps": [
                  {"type": "user_input", "content": [{"type": "text", "text": "ignored"}]},
                  {"type": "thought", "content": [{"type": "text", "text": "ignored"}]},
                  {"type": "model_output", "content": [{"type": "image"}, {"type": "text", "text": %s}]}]}
                """.formatted(quote(OUTPUT));
        server.expect(requestTo(URL)).andRespond(withSuccess(response, MediaType.APPLICATION_JSON));

        assertThat(grounder().ground(VIDEO_ID).facts()).hasSize(1);
    }

    @Test
    void API_key와_Provider_원문은_예외와_로그에_남지_않는다(CapturedOutput output) {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.FORBIDDEN)
                .body("{\"error\": \"" + PROVIDER_SECRET + " " + API_KEY + "\"}")
                .contentType(MediaType.APPLICATION_JSON));
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(PROVIDER_SECRET).contentType(MediaType.TEXT_PLAIN));

        for (int i = 0; i < 2; i++) {
            if (i == 1) {
                properties.setMaxAttempts(1);
            }
            assertThatThrownBy(() -> grounder().ground(VIDEO_ID))
                    .isInstanceOfSatisfying(VideoGroundingException.class, exception -> {
                        assertThat(exception).hasNoCause();
                        assertThat(exception.getMessage()).doesNotContain(PROVIDER_SECRET, API_KEY);
                    });
        }
        assertThat(output.getAll()).doesNotContain(PROVIDER_SECRET, API_KEY, "물은 증발해");
    }

    @Test
    void 실패한_호출은_Provider_오류_상태와_reason을_진단_로그로_남긴다(CapturedOutput output) {
        properties.setMaxAttempts(2);
        server.expect(ExpectedCount.times(2), requestTo(URL)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS)
                .body("""
                        {"error": {"code": 429, "message": "%s", "status": "RESOURCE_EXHAUSTED", "details": [
                         {"@type": "type.googleapis.com/google.rpc.ErrorInfo", "reason": "RATE_LIMIT_EXCEEDED"}]}}"""
                        .formatted(PROVIDER_SECRET))
                .contentType(MediaType.APPLICATION_JSON));

        assertFailure(() -> grounder().ground(VIDEO_ID), Failure.PROVIDER_ERROR);

        server.verify();
        assertThat(output.getAll())
                .contains("event=ai_provider_call provider=gemini operation=quiz_grounding model=gemini-test-model "
                        + "status=429 errorCode=RATE_LIMIT_EXCEEDED errorType=RESOURCE_EXHAUSTED latencyMs=")
                .contains("attempt=1 retry=true")
                .contains("attempt=2 retry=false")
                .doesNotContain(PROVIDER_SECRET, API_KEY);
    }

    @Test
    void 성공_상태_응답의_envelope만_진단_로그를_남기고_contentText_검증_실패는_남기지_않는다(CapturedOutput output) {
        String[] envelopeFailures = {"not json " + PROVIDER_SECRET, "{\"status\": \"incomplete\", \"steps\": []}",
                "{\"status\": \"completed\", \"steps\": [{\"type\": \"user_input\"}]}"};
        for (String body : envelopeFailures) {
            server.reset();
            server.expect(ExpectedCount.once(), requestTo(URL)).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
            assertFailure(() -> grounder().ground(VIDEO_ID), Failure.INVALID_RESPONSE);
            server.verify();
        }
        String envelopeLog = "event=ai_provider_call provider=gemini operation=quiz_grounding model=gemini-test-model "
                + "status=200 errorCode=- errorType=invalid_response latencyMs=";
        assertThat(output.getAll().split(envelopeLog, -1)).hasSize(4);

        server.reset();
        server.expect(ExpectedCount.once(), requestTo(URL)).andRespond(withSuccess(
                completed("{\"contentText\": \" \", \"facts\": [], \"note\": \"" + PROVIDER_SECRET + "\"}"),
                MediaType.APPLICATION_JSON));
        assertFailure(() -> grounder().ground(VIDEO_ID), Failure.INVALID_RESPONSE);
        server.verify();

        assertThat(output.getAll().split(envelopeLog, -1)).as("contentText 검증 실패는 공통 로그 대상이 아니다").hasSize(4);
        assertThat(output.getAll()).doesNotContain(PROVIDER_SECRET, API_KEY);
    }

    private GeminiVideoGrounder grounder() {
        return new GeminiVideoGrounder(builder.build(), properties, objectMapper, sleeps::add);
    }

    private String completed(String outputText) {
        return """
                {"id": "interaction-1", "status": "completed", "steps": [
                  {"type": "model_output", "content": [{"type": "text", "text": %s}]}]}
                """.formatted(quote(outputText));
    }

    private String quote(String text) {
        return objectMapper.writeValueAsString(text);
    }

    private void assertFailure(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, Failure failure) {
        assertThatThrownBy(call).isInstanceOfSatisfying(VideoGroundingException.class, exception -> {
            assertThat(exception.getFailure()).isEqualTo(failure);
            assertThat(exception).hasNoCause();
        });
    }
}
