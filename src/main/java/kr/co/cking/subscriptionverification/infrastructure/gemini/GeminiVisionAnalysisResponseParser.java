package kr.co.cking.subscriptionverification.infrastructure.gemini;

import java.util.List;
import java.util.Map;

import kr.co.cking.subscriptionverification.application.vision.VisionAnalysisResult;
import kr.co.cking.subscriptionverification.application.vision.VisionPlatform;
import kr.co.cking.subscriptionverification.application.vision.VisionSubscriptionState;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/** Gemini generateContent 응답을 Provider 독립 Vision 분석 결과로 엄격하게 변환한다. */
public class GeminiVisionAnalysisResponseParser {

    private final ObjectMapper objectMapper;

    public GeminiVisionAnalysisResponseParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** generateContent envelope에서 정상 종료된 첫 번째 candidate의 JSON text를 꺼낸다. */
    public String extractContent(String responseJson) {
        try {
            Map<String, Object> root = objectMapper.readValue(responseJson, new TypeReference<>() {
            });
            if (root == null || !(root.get("candidates") instanceof List<?> candidates) || candidates.isEmpty()) {
                throw new IllegalArgumentException("Gemini 응답에 candidates가 없습니다.");
            }
            if (!(candidates.getFirst() instanceof Map<?, ?> candidate)) {
                throw new IllegalArgumentException("Gemini candidate 형식이 올바르지 않습니다.");
            }
            if (!"STOP".equals(candidate.get("finishReason"))) {
                throw new IllegalArgumentException("Gemini 응답이 정상적으로 완료되지 않았습니다.");
            }
            if (!(candidate.get("content") instanceof Map<?, ?> content)
                    || !(content.get("parts") instanceof List<?> parts)) {
                throw new IllegalArgumentException("Gemini 응답에 content parts가 없습니다.");
            }
            for (Object partValue : parts) {
                if (partValue instanceof Map<?, ?> part
                        && !Boolean.TRUE.equals(part.get("thought"))
                        && part.get("text") instanceof String text
                        && !text.isBlank()) {
                    return normalizeContent(text);
                }
            }
            throw new IllegalArgumentException("Gemini 응답에 분석 JSON이 없습니다.");
        } catch (JacksonException | IllegalArgumentException exception) {
            throw new GeminiResponseParseException("Gemini 응답 envelope를 파싱할 수 없습니다.", exception);
        }
    }

    /** Provider JSON의 필수 필드와 enum·타입 범위를 검증해 Vision 결과로 변환한다. */
    public VisionAnalysisResult parse(String contentJson) {
        try {
            Map<String, Object> root = objectMapper.readValue(contentJson, new TypeReference<>() {
            });
            if (root == null) {
                throw new IllegalArgumentException("Gemini 분석 JSON은 객체여야 합니다.");
            }
            requireFields(root, List.of(
                    "platform", "subscriptionState", "detectedText", "observedChannelName",
                    "observedChannelHandle", "evidenceSufficient", "confidence"));
            nullableString(root.get("detectedText"), "detectedText");
            return new VisionAnalysisResult(
                    parseEnum(root.get("platform"), VisionPlatform.class, "platform"),
                    nullableString(root.get("observedChannelName"), "observedChannelName"),
                    nullableString(root.get("observedChannelHandle"), "observedChannelHandle"),
                    parseEnum(root.get("subscriptionState"), VisionSubscriptionState.class, "subscriptionState"),
                    requiredBoolean(root.get("evidenceSufficient"), "evidenceSufficient"),
                    requiredConfidence(root.get("confidence")));
        } catch (JacksonException | IllegalArgumentException exception) {
            throw new GeminiResponseParseException("Gemini 분석 JSON을 파싱할 수 없습니다.", exception);
        }
    }

    private void requireFields(Map<String, Object> root, List<String> fields) {
        for (String field : fields) {
            if (!root.containsKey(field)) {
                throw new IllegalArgumentException("Gemini 분석 JSON에 필수 필드가 없습니다: " + field);
            }
        }
    }

    private <T extends Enum<T>> T parseEnum(Object value, Class<T> enumType, String field) {
        if (!(value instanceof String raw) || raw.isBlank()) {
            throw new IllegalArgumentException("Gemini 분석 JSON의 " + field + " 값이 올바르지 않습니다.");
        }
        try {
            return Enum.valueOf(enumType, raw);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Gemini 분석 JSON의 " + field + " 값이 알려지지 않았습니다.", exception);
        }
    }

    private String nullableString(Object value, String field) {
        if (value == null) {
            return null;
        }
        if (!(value instanceof String text)) {
            throw new IllegalArgumentException("Gemini 분석 JSON의 " + field + " 값이 문자열이 아닙니다.");
        }
        return text;
    }

    private boolean requiredBoolean(Object value, String field) {
        if (!(value instanceof Boolean result)) {
            throw new IllegalArgumentException("Gemini 분석 JSON의 " + field + " 값이 boolean이 아닙니다.");
        }
        return result;
    }

    private double requiredConfidence(Object value) {
        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException("Gemini 분석 JSON의 confidence 값이 숫자가 아닙니다.");
        }
        double confidence = number.doubleValue();
        if (!Double.isFinite(confidence) || confidence < 0.0 || confidence > 1.0) {
            throw new IllegalArgumentException("Gemini 분석 JSON의 confidence 범위가 올바르지 않습니다.");
        }
        return confidence;
    }

    private String normalizeContent(String content) {
        String normalized = content.trim();
        if (!normalized.startsWith("```")) {
            return normalized;
        }

        int firstLineEnd = normalized.indexOf('\n');
        if (firstLineEnd < 0 || !normalized.endsWith("```")) {
            throw new IllegalArgumentException("Gemini 분석 JSON code fence가 닫히지 않았습니다.");
        }
        String body = normalized.substring(firstLineEnd + 1, normalized.length() - 3).trim();
        if (body.isEmpty()) {
            throw new IllegalArgumentException("Gemini 분석 JSON이 비어 있습니다.");
        }
        return body;
    }
}
