package kr.co.cking.quiz.generation.gemini;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 현재 Core Engine 결과 계약에 맞는 Gemini response_schema를 만든다. */
final class GeminiSchemaFactory {

    private GeminiSchemaFactory() {
    }

    static Map<String, Object> quizSchema(int questionCount, int optionCount) {
        Map<String, Object> questionProperties = new LinkedHashMap<>();
        questionProperties.put("question", stringSchema());
        questionProperties.put("options", arraySchema(stringSchema(), optionCount, optionCount));
        questionProperties.put("correctOptionIndex", integerSchema(0, optionCount - 1));
        questionProperties.put("explanation", stringSchema());
        questionProperties.put("sourceEvidence", stringSchema());

        Map<String, Object> questionSchema = new LinkedHashMap<>();
        questionSchema.put("type", "object");
        questionSchema.put("properties", questionProperties);
        questionSchema.put("required", List.of(
                "question", "options", "correctOptionIndex", "explanation", "sourceEvidence"));
        questionSchema.put("additionalProperties", false);

        Map<String, Object> quizProperties = new LinkedHashMap<>();
        quizProperties.put("questions", arraySchema(questionSchema, questionCount, questionCount));
        quizProperties.put("promptVersion", stringSchema());

        Map<String, Object> quizSchema = new LinkedHashMap<>();
        quizSchema.put("type", "object");
        quizSchema.put("properties", quizProperties);
        quizSchema.put("required", List.of("questions", "promptVersion"));
        quizSchema.put("additionalProperties", false);
        return quizSchema;
    }

    private static Map<String, Object> stringSchema() {
        return Map.of("type", "string");
    }

    private static Map<String, Object> integerSchema(int minimum, int maximum) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "integer");
        schema.put("minimum", minimum);
        schema.put("maximum", maximum);
        return schema;
    }

    private static Map<String, Object> arraySchema(Object itemSchema, int minimum, int maximum) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "array");
        schema.put("items", itemSchema);
        schema.put("minItems", minimum);
        schema.put("maxItems", maximum);
        return schema;
    }
}
