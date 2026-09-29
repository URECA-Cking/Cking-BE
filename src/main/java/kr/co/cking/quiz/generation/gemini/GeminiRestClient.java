package kr.co.cking.quiz.generation.gemini;

import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;

import kr.co.cking.quiz.generation.QuizProviderTimeoutException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.ObjectMapper;

/** Gemini generateContent REST API 호출을 담당한다. */
public class GeminiRestClient implements GeminiApiClient {

    private final RestClient restClient;
    private final GeminiQuizProperties properties;
    private final GeminiApiResponseExtractor responseExtractor;
    private final ObjectMapper objectMapper;

    public GeminiRestClient(RestClient restClient, GeminiQuizProperties properties, ObjectMapper objectMapper) {
        this.restClient = restClient;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.responseExtractor = new GeminiApiResponseExtractor(objectMapper);
    }

    @Override
    public String generateContent(String prompt, int questionCount, int optionCount) {
        if (properties.getApiKey() == null || properties.getApiKey().isBlank()) {
            throw new GeminiApiException("Gemini API key가 설정되지 않았습니다.");
        }

        int maxAttempts = Math.max(1, properties.getMaxAttempts());
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                String response = request(prompt, questionCount, optionCount);
                return responseExtractor.extractText(response);
            } catch (RestClientResponseException exception) {
                int status = exception.getStatusCode().value();
                if (!isRetryableStatus(status) || attempt == maxAttempts) {
                    throw new GeminiApiException("Gemini API 요청이 실패했습니다. status=" + status, exception);
                }
                pauseBeforeRetry();
            } catch (ResourceAccessException exception) {
                if (isTimeout(exception)) {
                    throw new QuizProviderTimeoutException("Gemini API 응답 시간이 초과되었습니다.", exception);
                } else {
                    throw new GeminiApiException("Gemini API 네트워크 요청이 실패했습니다.", exception);
                }
            } catch (GeminiResponseException exception) {
                throw exception;
            } catch (RestClientException exception) {
                if (isTimeout(exception)) {
                    throw new QuizProviderTimeoutException("Gemini API 응답 시간이 초과되었습니다.", exception);
                }
                throw new GeminiApiException("Gemini API 요청이 실패했습니다.", exception);
            }
        }
        throw new GeminiApiException("Gemini API 요청이 실패했습니다.");
    }

    private String request(String prompt, int questionCount, int optionCount) {
        return restClient.post()
                .uri(uriBuilder -> uriBuilder
                        .path("/models/{model}:generateContent")
                        .build(properties.getModel()))
                .header("x-goog-api-key", properties.getApiKey())
                .body(requestBody(prompt, questionCount, optionCount))
                .retrieve()
                .body(String.class);
    }

    private Map<String, Object> requestBody(String prompt, int questionCount, int optionCount) {
        Map<String, Object> part = Map.of("text", prompt);
        Map<String, Object> content = Map.of("parts", List.of(part));

        Map<String, Object> generationConfig = new LinkedHashMap<>();
        generationConfig.put("responseFormat", Map.of("text", Map.of(
                "mimeType", "application/json",
                "schema", GeminiSchemaFactory.quizSchema(questionCount, optionCount))));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("contents", List.of(content));
        body.put("generationConfig", generationConfig);
        return body;
    }

    private boolean isRetryableStatus(int status) {
        return status == 429 || status >= 500;
    }

    private void pauseBeforeRetry() {
        try {
            Thread.sleep(1_000);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new GeminiApiException("Gemini API 재시도가 중단되었습니다.", exception);
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
}
