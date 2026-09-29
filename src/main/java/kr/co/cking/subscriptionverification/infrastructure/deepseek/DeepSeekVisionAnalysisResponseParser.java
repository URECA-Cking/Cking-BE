package kr.co.cking.subscriptionverification.infrastructure.deepseek;

import java.util.List;
import java.util.Map;

import kr.co.cking.subscriptionverification.application.vision.VisionAnalysisResult;
import kr.co.cking.subscriptionverification.application.vision.VisionPlatform;
import kr.co.cking.subscriptionverification.application.vision.VisionSubscriptionState;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/** DeepSeek JSON 응답을 Provider 독립 Vision 분석 결과로 엄격하게 변환한다. */
public class DeepSeekVisionAnalysisResponseParser {

    private final ObjectMapper objectMapper;

    public DeepSeekVisionAnalysisResponseParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** Chat Completions envelope에서 첫 번째 모델 JSON 문자열을 꺼낸다. */
    public String extractContent(String responseJson) {
        try {
            DeepSeekChatCompletionResponse response = objectMapper.readValue(
                    responseJson, DeepSeekChatCompletionResponse.class);
            if (response == null || response.choices() == null || response.choices().isEmpty()) {
                throw new IllegalArgumentException("DeepSeek 응답에 choices가 없습니다.");
            }
            DeepSeekChoice choice = response.choices().getFirst();
            if (choice == null || choice.message() == null || isBlank(choice.message().content())) {
                throw new IllegalArgumentException("DeepSeek 응답에 분석 JSON이 없습니다.");
            }
            return choice.message().content().trim();
        } catch (JacksonException exception) {
            throw new IllegalArgumentException("DeepSeek 응답 envelope를 파싱할 수 없습니다.", exception);
        }
    }

    /** Provider JSON의 필수 필드와 enum·타입 범위를 검증해 Vision 결과로 변환한다. */
    public VisionAnalysisResult parse(String contentJson) {
        try {
            Map<String, Object> root = objectMapper.readValue(contentJson, new TypeReference<>() {
            });
            if (root == null) {
                throw new IllegalArgumentException("DeepSeek 분석 JSON은 객체여야 합니다.");
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
        } catch (JacksonException exception) {
            throw new IllegalArgumentException("DeepSeek 분석 JSON을 파싱할 수 없습니다.", exception);
        }
    }

    /** 응답이 요구된 필드를 하나도 생략하지 않았는지 확인한다. */
    private void requireFields(Map<String, Object> root, List<String> fields) {
        for (String field : fields) {
            if (!root.containsKey(field)) {
                throw new IllegalArgumentException("DeepSeek 분석 JSON에 필수 필드가 없습니다: " + field);
            }
        }
    }

    /** 문자열 enum만 받아 정의되지 않은 Provider 값을 거부한다. */
    private <T extends Enum<T>> T parseEnum(Object value, Class<T> enumType, String field) {
        if (!(value instanceof String raw) || raw.isBlank()) {
            throw new IllegalArgumentException("DeepSeek 분석 JSON의 " + field + " 값이 올바르지 않습니다.");
        }
        try {
            return Enum.valueOf(enumType, raw);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("DeepSeek 분석 JSON의 " + field + " 값이 알려지지 않았습니다.", exception);
        }
    }

    /** null 또는 문자열만 관측 채널 필드로 허용한다. */
    private String nullableString(Object value, String field) {
        if (value == null) {
            return null;
        }
        if (!(value instanceof String text)) {
            throw new IllegalArgumentException("DeepSeek 분석 JSON의 " + field + " 값이 문자열이 아닙니다.");
        }
        return text;
    }

    /** boolean 타입의 증거 충분성만 허용한다. */
    private boolean requiredBoolean(Object value, String field) {
        if (!(value instanceof Boolean result)) {
            throw new IllegalArgumentException("DeepSeek 분석 JSON의 " + field + " 값이 boolean이 아닙니다.");
        }
        return result;
    }

    /** 0.0 이상 1.0 이하의 유한한 숫자 confidence만 허용한다. */
    private double requiredConfidence(Object value) {
        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException("DeepSeek 분석 JSON의 confidence 값이 숫자가 아닙니다.");
        }
        double confidence = number.doubleValue();
        if (!Double.isFinite(confidence) || confidence < 0.0 || confidence > 1.0) {
            throw new IllegalArgumentException("DeepSeek 분석 JSON의 confidence 범위가 올바르지 않습니다.");
        }
        return confidence;
    }

    /** 비어 있거나 공백뿐인 응답 문자열인지 확인한다. */
    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
