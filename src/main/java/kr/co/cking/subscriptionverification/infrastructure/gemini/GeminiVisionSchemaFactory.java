package kr.co.cking.subscriptionverification.infrastructure.gemini;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Gemini Structured Output에 전달할 구독 인증 관측 결과 JSON Schema를 만든다. */
final class GeminiVisionSchemaFactory {

    private GeminiVisionSchemaFactory() {
    }

    static Map<String, Object> analysisSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("platform", enumSchema("YOUTUBE", "OTHER", "UNKNOWN"));
        properties.put("subscriptionState", enumSchema("SUBSCRIBED", "NOT_SUBSCRIBED", "UNKNOWN"));
        properties.put("detectedText", nullableStringSchema());
        properties.put("observedChannelName", nullableStringSchema());
        properties.put("observedChannelHandle", nullableStringSchema());
        properties.put("evidenceSufficient", Map.of("type", "boolean"));
        properties.put("confidence", Map.of("type", "number", "minimum", 0.0, "maximum", 1.0));

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of(
                "platform", "subscriptionState", "detectedText", "observedChannelName",
                "observedChannelHandle", "evidenceSufficient", "confidence"));
        schema.put("additionalProperties", false);
        return schema;
    }

    private static Map<String, Object> enumSchema(String... values) {
        return Map.of("type", "string", "enum", List.of(values));
    }

    private static Map<String, Object> nullableStringSchema() {
        return Map.of("type", List.of("string", "null"));
    }
}
