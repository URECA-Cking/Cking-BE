package kr.co.cking.quiz.generation;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/** Provider의 JSON 문자열을 구조화된 생성 결과로만 변환한다. 의미 검증은 Validator가 담당한다. */
public class QuizResponseParser {

    private final ObjectMapper objectMapper;

    public QuizResponseParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public GeneratedQuiz parse(String json) {
        if (json == null || json.isBlank()) {
            throw new QuizResponseParseException("Provider 응답이 비어 있습니다.");
        }

        Map<String, Object> root;
        try {
            root = objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {
            });
        } catch (JacksonException e) {
            throw new QuizResponseParseException("Provider 응답 JSON을 파싱할 수 없습니다.", e);
        }
        if (root == null) {
            throw new QuizResponseParseException("Provider 응답 JSON이 객체가 아닙니다.");
        }

        Object rawQuestions = root.get("questions");
        if (!(rawQuestions instanceof List<?> questionValues)) {
            throw new QuizResponseParseException("questions는 배열이어야 합니다.");
        }

        List<GeneratedQuestion> questions = new ArrayList<>();
        for (int index = 0; index < questionValues.size(); index++) {
            Object rawQuestion = questionValues.get(index);
            if (!(rawQuestion instanceof Map<?, ?> question)) {
                throw typeError("questions[" + index + "]");
            }
            questions.add(new GeneratedQuestion(
                    requiredString(question, "question", index),
                    requiredOptions(question, index),
                    requiredInteger(question, "correctOptionIndex", index),
                    requiredString(question, "explanation", index),
                    requiredString(question, "sourceEvidence", index)
            ));
        }

        String promptVersion = optionalString(root, "promptVersion", QuizPromptVersion.V1.value());
        return new GeneratedQuiz(questions, promptVersion);
    }

    private List<String> requiredOptions(Map<?, ?> question, int index) {
        Object rawOptions = question.get("options");
        if (!(rawOptions instanceof List<?> values)) {
            throw typeError("questions[" + index + "].options");
        }
        List<String> options = new ArrayList<>();
        for (int optionIndex = 0; optionIndex < values.size(); optionIndex++) {
            Object option = values.get(optionIndex);
            if (!(option instanceof String value)) {
                throw typeError("questions[" + index + "].options[" + optionIndex + "]");
            }
            options.add(value);
        }
        return options;
    }

    private String requiredString(Map<?, ?> object, String field, int index) {
        Object value = object.get(field);
        if (!(value instanceof String string)) {
            throw typeError("questions[" + index + "]." + field);
        }
        return string;
    }

    private int requiredInteger(Map<?, ?> object, String field, int index) {
        Object value = object.get(field);
        if (!(value instanceof Number number)
                || number.doubleValue() != Math.rint(number.doubleValue())) {
            throw typeError("questions[" + index + "]." + field);
        }
        return number.intValue();
    }

    private String optionalString(Map<?, ?> object, String field, String defaultValue) {
        Object value = object.get(field);
        if (value == null) {
            return defaultValue;
        }
        if (!(value instanceof String string)) {
            throw typeError(field);
        }
        return string;
    }

    private QuizResponseParseException typeError(String field) {
        return new QuizResponseParseException(field + "의 JSON 타입이 올바르지 않습니다.");
    }
}
