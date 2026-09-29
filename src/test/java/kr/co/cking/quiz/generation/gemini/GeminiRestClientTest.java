package kr.co.cking.quiz.generation.gemini;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import kr.co.cking.quiz.generation.QuizProviderTimeoutException;
import kr.co.cking.quiz.generation.GeneratedQuiz;
import kr.co.cking.quiz.generation.QuizGenerationInput;
import kr.co.cking.quiz.generation.QuizGenerationValidator;
import kr.co.cking.quiz.generation.QuizPromptTemplate;
import kr.co.cking.quiz.generation.QuizPromptVersion;
import kr.co.cking.quiz.generation.QuizResponseParser;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ExtendWith(MockitoExtension.class)
class GeminiRestClientTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private HttpServer server;
    private AtomicReference<String> requestBody;
    private AtomicReference<String> apiKey;
    private AtomicInteger requestCount;

    @BeforeEach
    void setUp() throws IOException {
        requestBody = new AtomicReference<>();
        apiKey = new AtomicReference<>();
        requestCount = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void 정상_요청은_공식_헤더와_Structured_Output_schema를_전달한다() throws Exception {
        server.createContext("/v1beta/models/test-model:generateContent", exchange -> {
            capture(exchange);
            respond(exchange, 200, """
                    {"candidates":[{"finishReason":"STOP","content":{"parts":[{"text":"{\\\"questions\\\":[]}"}]}}]}
                    """);
        });

        String result = client(1, 4).generateContent("prompt text", 1, 4);

        assertThat(result).contains("questions");
        assertThat(apiKey.get()).isEqualTo("test-key");
        Map<String, Object> body = objectMapper.readValue(requestBody.get(), new TypeReference<>() {
        });
        assertThat(body).containsKey("contents");
        Map<String, Object> generationConfig = map(body.get("generationConfig"));
        assertThat(generationConfig).doesNotContainKeys("response_mime_type", "response_schema", "responseMimeType", "responseSchema");
        Map<String, Object> textFormat = map(map(generationConfig.get("responseFormat")).get("text"));
        assertThat(textFormat.get("mimeType")).isEqualTo("application/json");
        Map<String, Object> schema = map(textFormat.get("schema"));
        assertThat(schema.get("type")).isEqualTo("object");
        assertThat(map(schema.get("properties"))).containsKeys("questions", "promptVersion");
        Map<String, Object> questions = map(map(schema.get("properties")).get("questions"));
        assertThat(questions.get("minItems")).isEqualTo(1);
        assertThat(questions.get("maxItems")).isEqualTo(1);
    }

    @Test
    void API_key가_없으면_호출하지_않고_Provider_오류다() {
        assertThatThrownBy(() -> client(1, 4, "").generateContent("prompt", 1, 4))
                .isInstanceOf(GeminiApiException.class)
                .hasMessageContaining("API key");
        assertThat(requestCount).hasValue(0);
    }

    @Test
    void _429는_설정된_횟수만큼_재시도한다() {
        server.createContext("/v1beta/models/test-model:generateContent", exchange -> {
            capture(exchange);
            int count = requestCount.get();
            if (count < 2) {
                respond(exchange, 429, "{\"error\":{\"status\":\"RESOURCE_EXHAUSTED\"}}");
            } else {
                respond(exchange, 200, "{\"candidates\":[{\"finishReason\":\"STOP\",\"content\":{\"parts\":[{\"text\":\"ok\"}]}}]}");
            }
        });

        long startedAt = System.nanoTime();
        assertThat(client(2, 1_000).generateContent("prompt", 1, 4)).isEqualTo("ok");
        assertThat(Duration.ofNanos(System.nanoTime() - startedAt)).isGreaterThanOrEqualTo(Duration.ofMillis(900));
        assertThat(requestCount).hasValue(2);
    }

    @Test
    void _4xx는_재시도하지_않는다() {
        server.createContext("/v1beta/models/test-model:generateContent", exchange -> {
            capture(exchange);
            respond(exchange, 400, "{\"error\":{\"status\":\"INVALID_ARGUMENT\"}}");
        });

        assertThatThrownBy(() -> client(3, 1_000).generateContent("prompt", 1, 4))
                .isInstanceOf(GeminiApiException.class)
                .hasMessageContaining("400");
        assertThat(requestCount).hasValue(1);
    }

    @Test
    void _5xx는_재시도_후_실패하면_Provider_오류다() {
        server.createContext("/v1beta/models/test-model:generateContent", exchange -> {
            capture(exchange);
            respond(exchange, 503, "{\"error\":{\"status\":\"UNAVAILABLE\"}}");
        });

        assertThatThrownBy(() -> client(2, 1_000).generateContent("prompt", 1, 4))
                .isInstanceOf(GeminiApiException.class)
                .hasMessageContaining("503");
        assertThat(requestCount).hasValue(2);
    }

    @Test
    @Timeout(3)
    void read_timeout은_QuizProviderTimeoutException으로_변환된다() {
        server.createContext("/v1beta/models/test-model:generateContent", exchange -> {
            capture(exchange);
            try {
                Thread.sleep(250);
                respond(exchange, 200, "{\"candidates\":[{\"finishReason\":\"STOP\",\"content\":{\"parts\":[{\"text\":\"ok\"}]}}]}");
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        });

        assertThatThrownBy(() -> client(1, 20).generateContent("prompt", 1, 4))
                .isInstanceOf(QuizProviderTimeoutException.class);
    }

    @Test
    @Timeout(3)
    void read_timeout은_과금될_수_있으므로_재시도하지_않는다() {
        server.createContext("/v1beta/models/test-model:generateContent", exchange -> {
            capture(exchange);
            try {
                Thread.sleep(250);
                respond(exchange, 200, "{\"candidates\":[]}");
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        });

        assertThatThrownBy(() -> client(2, 20).generateContent("prompt", 1, 4))
                .isInstanceOf(QuizProviderTimeoutException.class);
        assertThat(requestCount).hasValue(1);
    }

    @Test
    void 실제_HTTP_응답은_Core_Parser와_Validator를_거쳐_퀴즈가_된다() throws Exception {
        QuizGenerationInput input = new QuizGenerationInput("서울은 대한민국의 수도다.", 1, 4);
        String quizJson = """
                {"questions":[{"question":"대한민국의 수도는?","options":["서울","부산","대전","광주"],
                "correctOptionIndex":0,"explanation":"서울이 수도다.","sourceEvidence":"서울은 대한민국의 수도다."}],
                "promptVersion":"quiz-mcq-v1"}
                """;
        server.createContext("/v1beta/models/test-model:generateContent", exchange -> {
            capture(exchange);
            respond(exchange, 200, objectMapper.writeValueAsString(Map.of("candidates", List.of(Map.of(
                    "finishReason", "STOP", "content", Map.of("parts", List.of(Map.of("text", quizJson))))))));
        });
        GeminiQuizGenerator generator = new GeminiQuizGenerator(
                client(1, 1_000), QuizPromptTemplate.forVersion(QuizPromptVersion.V1),
                new QuizResponseParser(objectMapper), new QuizGenerationValidator());

        GeneratedQuiz result = generator.generate(input);

        assertThat(result.questions()).hasSize(1);
        assertThat(result.questions().get(0).correctOptionIndex()).isZero();
        assertThat(result.questions().get(0).sourceEvidence()).isEqualTo(input.contentText());
        Map<String, Object> body = objectMapper.readValue(requestBody.get(), new TypeReference<>() {});
        Map<String, Object> content = map(((List<?>) body.get("contents")).get(0));
        Map<String, Object> part = map(((List<?>) content.get("parts")).get(0));
        assertThat((String) part.get("text"))
                .contains(input.contentText(), "Prompt version: quiz-mcq-v1", "exactly 1 question(s)");
    }

    private GeminiRestClient client(int maxAttempts, long readTimeoutMillis) {
        return client(maxAttempts, readTimeoutMillis, "test-key");
    }

    private GeminiRestClient client(int maxAttempts, long readTimeoutMillis, String key) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(1));
        factory.setReadTimeout(Duration.ofMillis(readTimeoutMillis));
        RestClient restClient = RestClient.builder()
                .baseUrl("http://localhost:" + server.getAddress().getPort() + "/v1beta")
                .requestFactory(factory)
                .build();
        GeminiQuizProperties properties = new GeminiQuizProperties();
        properties.setApiKey(key);
        properties.setModel("test-model");
        properties.setMaxAttempts(maxAttempts);
        return new GeminiRestClient(restClient, properties, objectMapper);
    }

    private void capture(HttpExchange exchange) throws IOException {
        requestCount.incrementAndGet();
        apiKey.set(exchange.getRequestHeaders().getFirst("x-goog-api-key"));
        requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
    }

    private void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }
}
