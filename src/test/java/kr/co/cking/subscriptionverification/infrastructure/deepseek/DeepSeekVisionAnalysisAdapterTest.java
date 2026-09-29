package kr.co.cking.subscriptionverification.infrastructure.deepseek;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import kr.co.cking.subscriptionverification.application.vision.VisionAnalysisException;
import kr.co.cking.subscriptionverification.application.vision.VisionAnalysisFailureType;
import kr.co.cking.subscriptionverification.application.vision.VisionAnalysisRequest;
import kr.co.cking.subscriptionverification.application.vision.VisionAnalysisResult;
import kr.co.cking.subscriptionverification.application.vision.VisionPlatform;
import kr.co.cking.subscriptionverification.application.vision.VisionSubscriptionState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

class DeepSeekVisionAnalysisAdapterTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private HttpServer server;
    private AtomicReference<String> requestBody;
    private AtomicReference<String> authorization;
    private AtomicInteger requestCount;

    @BeforeEach
    void setUp() throws IOException {
        requestBody = new AtomicReference<>();
        authorization = new AtomicReference<>();
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
    void 정상_응답은_DeepSeek_요청_형식으로_전달하고_Vision_결과로_변환한다() throws Exception {
        server.createContext("/chat/completions", exchange -> {
            capture(exchange);
            respond(exchange, 200, completion("""
                    {"platform":"YOUTUBE","subscriptionState":"SUBSCRIBED","detectedText":"구독중",
                    "observedChannelName":"채널 이름","observedChannelHandle":"@channelhandle",
                    "evidenceSufficient":true,"confidence":0.98}
                    """));
        });

        VisionAnalysisResult result = client(2, 1_000).analyze(request());

        assertThat(result).isEqualTo(new VisionAnalysisResult(
                VisionPlatform.YOUTUBE, "채널 이름", "@channelhandle",
                VisionSubscriptionState.SUBSCRIBED, true, 0.98));
        assertThat(authorization.get()).isEqualTo("Bearer test-key");
        Map<String, Object> body = objectMapper.readValue(requestBody.get(), new TypeReference<>() {
        });
        assertThat(body).containsEntry("model", "deepseek-flash");
        assertThat(body).containsEntry("response_format", Map.of("type", "json_object"));
        assertThat(body).containsEntry("thinking", Map.of("type", "disabled"));
        List<?> messages = (List<?>) body.get("messages");
        assertThat(messages).hasSize(2);
        Map<String, Object> userMessage = map(messages.get(1));
        List<?> content = (List<?>) userMessage.get("content");
        Map<String, Object> imagePart = map(content.get(1));
        assertThat(map(imagePart.get("image_url")).get("url")).isEqualTo("data:image/jpeg;base64,/9j/");
    }

    @Test
    void malformed_JSON은_재시도_가능한_기술_오류로_변환한다() {
        server.createContext("/chat/completions", exchange -> {
            capture(exchange);
            respond(exchange, 200, completion("{"));
        });

        assertThatThrownBy(() -> client(1, 1_000).analyze(request()))
                .isInstanceOf(VisionAnalysisException.class)
                .extracting(exception -> ((VisionAnalysisException) exception).failureType())
                .isEqualTo(VisionAnalysisFailureType.RETRYABLE);
    }

    @Test
    void 필수_필드_누락은_재시도_가능한_기술_오류로_변환한다() {
        server.createContext("/chat/completions", exchange -> {
            capture(exchange);
            respond(exchange, 200, completion("""
                    {"platform":"YOUTUBE","subscriptionState":"UNKNOWN","detectedText":null,
                    "observedChannelName":null,"observedChannelHandle":null,"evidenceSufficient":false}
                    """));
        });

        assertThatThrownBy(() -> client(1, 1_000).analyze(request()))
                .isInstanceOf(VisionAnalysisException.class)
                .extracting(exception -> ((VisionAnalysisException) exception).failureType())
                .isEqualTo(VisionAnalysisFailureType.RETRYABLE);
    }

    @Test
    void 알수없는_enum_값은_재시도_가능한_기술_오류로_변환한다() {
        server.createContext("/chat/completions", exchange -> {
            capture(exchange);
            respond(exchange, 200, completion("""
                    {"platform":"YOUTUBE","subscriptionState":"MAYBE","detectedText":null,
                    "observedChannelName":null,"observedChannelHandle":null,"evidenceSufficient":false,"confidence":0.1}
                    """));
        });

        assertThatThrownBy(() -> client(1, 1_000).analyze(request()))
                .isInstanceOf(VisionAnalysisException.class)
                .extracting(exception -> ((VisionAnalysisException) exception).failureType())
                .isEqualTo(VisionAnalysisFailureType.RETRYABLE);
    }

    @Test
    @Timeout(3)
    void timeout은_재시도_가능한_기술_오류로_변환한다() {
        server.createContext("/chat/completions", exchange -> {
            capture(exchange);
            try {
                Thread.sleep(250);
                respond(exchange, 200, completion("{}"));
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        });

        assertThatThrownBy(() -> client(1, 20).analyze(request()))
                .isInstanceOf(VisionAnalysisException.class)
                .extracting(exception -> ((VisionAnalysisException) exception).failureType())
                .isEqualTo(VisionAnalysisFailureType.RETRYABLE);
    }

    @Test
    void _429와_5xx는_설정된_횟수만큼_재시도한_뒤_재시도_가능한_기술_오류다() {
        server.createContext("/chat/completions", exchange -> {
            capture(exchange);
            respond(exchange, requestCount.get() == 1 ? 429 : 503, "{\"error\":{\"message\":\"temporary\"}}");
        });

        assertThatThrownBy(() -> client(2, 1_000).analyze(request()))
                .isInstanceOf(VisionAnalysisException.class)
                .extracting(exception -> ((VisionAnalysisException) exception).failureType())
                .isEqualTo(VisionAnalysisFailureType.RETRYABLE);
        assertThat(requestCount).hasValue(2);
    }

    @Test
    void _400_계열은_재시도하지_않는_기술_오류다() {
        server.createContext("/chat/completions", exchange -> {
            capture(exchange);
            respond(exchange, 400, "{\"error\":{\"message\":\"bad request\"}}");
        });

        assertThatThrownBy(() -> client(3, 1_000).analyze(request()))
                .isInstanceOf(VisionAnalysisException.class)
                .extracting(exception -> ((VisionAnalysisException) exception).failureType())
                .isEqualTo(VisionAnalysisFailureType.NON_RETRYABLE);
        assertThat(requestCount).hasValue(1);
    }

    /** 테스트용 정규화 JPEG와 동결 채널 입력을 만든다. */
    private VisionAnalysisRequest request() {
        return new VisionAnalysisRequest(new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff}, "대상 채널", "@targetchannel");
    }

    /** 로컬 Mock HTTP Server를 향하는 DeepSeek Adapter를 만든다. */
    private DeepSeekVisionAnalysisAdapter client(int maxAttempts, long readTimeoutMillis) {
        DeepSeekVisionAnalysisProperties properties = new DeepSeekVisionAnalysisProperties();
        properties.setApiKey("test-key");
        properties.setEndpoint("http://localhost:" + server.getAddress().getPort() + "/chat/completions");
        properties.setMaxAttempts(maxAttempts);
        properties.setRetryBackoff(Duration.ZERO);
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(1));
        factory.setReadTimeout(Duration.ofMillis(readTimeoutMillis));
        return new DeepSeekVisionAnalysisAdapter(
                RestClient.builder().requestFactory(factory).build(), properties,
                new DeepSeekVisionAnalysisResponseParser(objectMapper), objectMapper);
    }

    /** Chat Completions envelope 형태로 Provider JSON 문자열을 감싼다. */
    private String completion(String content) throws IOException {
        return objectMapper.writeValueAsString(Map.of(
                "choices", List.of(Map.of("message", Map.of("content", content))),
                "usage", Map.of("prompt_tokens", 1, "completion_tokens", 2, "total_tokens", 3)));
    }

    /** Mock Server 요청 횟수·헤더·본문을 검사 가능한 메모리에만 보관한다. */
    private void capture(HttpExchange exchange) throws IOException {
        requestCount.incrementAndGet();
        authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
        requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
    }

    /** Mock Server가 지정한 HTTP 상태와 JSON 응답을 반환한다. */
    private void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    /** JSON 역직렬화 결과를 테스트 검증용 Map으로 변환한다. */
    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }
}
