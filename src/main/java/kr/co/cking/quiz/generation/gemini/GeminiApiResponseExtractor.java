package kr.co.cking.quiz.generation.gemini;

import java.util.List;
import java.util.Map;

import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/** Gemini generateContent envelope에서 모델이 생성한 text만 꺼내고 최소 정규화한다. */
public class GeminiApiResponseExtractor {

    private final ObjectMapper objectMapper;

    public GeminiApiResponseExtractor(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String extractText(String responseJson) {
        if (responseJson == null || responseJson.isBlank()) {
            throw new GeminiResponseException("Gemini 응답이 비어 있습니다.");
        }

        try {
            Map<String, Object> root = objectMapper.readValue(
                    responseJson, new TypeReference<Map<String, Object>>() {
                    });
            return normalizeText(findText(root));
        } catch (GeminiResponseException exception) {
            throw exception;
        } catch (JacksonException | ClassCastException exception) {
            throw new GeminiResponseException("Gemini 응답 envelope를 파싱할 수 없습니다.", exception);
        }
    }

    private String findText(Map<String, Object> root) {
        Object candidatesValue = root.get("candidates");
        if (!(candidatesValue instanceof List<?> candidates) || candidates.isEmpty()) {
            throw new GeminiResponseException("Gemini 응답에 candidates가 없습니다.");
        }
        if (!(candidates.get(0) instanceof Map<?, ?> candidate)) {
            throw new GeminiResponseException("Gemini candidate 형식이 올바르지 않습니다.");
        }
        if (!"STOP".equals(candidate.get("finishReason"))) {
            throw new GeminiResponseException("Gemini 응답이 정상적으로 완료되지 않았습니다.");
        }
        if (!(candidate.get("content") instanceof Map<?, ?> content)) {
            throw new GeminiResponseException("Gemini 응답에 content가 없습니다.");
        }
        if (!(content.get("parts") instanceof List<?> parts) || parts.isEmpty()) {
            throw new GeminiResponseException("Gemini 응답에 parts가 없습니다.");
        }
        for (Object partValue : parts) {
            if (partValue instanceof Map<?, ?> part && part.get("text") instanceof String text
                    && !text.isBlank()) {
                return text;
            }
        }
        throw new GeminiResponseException("Gemini 응답에 유효한 text가 없습니다.");
    }

    private String normalizeText(String text) {
        String normalized = text.trim();
        if (!normalized.startsWith("```")) {
            return normalized;
        }

        int firstLineEnd = normalized.indexOf('\n');
        if (firstLineEnd < 0 || !normalized.endsWith("```")) {
            throw new GeminiResponseException("Gemini 응답 code fence가 닫히지 않았습니다.");
        }
        String body = normalized.substring(firstLineEnd + 1, normalized.length() - 3).trim();
        if (body.isEmpty()) {
            throw new GeminiResponseException("Gemini 응답 JSON이 비어 있습니다.");
        }
        return body;
    }
}
