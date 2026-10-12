package kr.co.cking.quiz.judge;

import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ObjectReader;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Provider가 돌려준 평가 JSON 문자열을 {@link QuizJudgePrompt#responseSchema()} 구조로만 바꾼다. 문항 번호의 범위·중복과
 * reason 내용은 {@link QuizJudgeEvaluationValidator}가 검사한다.
 *
 * <p>모든 객체는 정의한 필드만 모두 가져야 하고, 같은 키가 두 번 나오거나 JSON 뒤에 다른 값이 있으면 거부한다.
 * {@code questionNo}는 JSON 정수여야 하며 verdict는 schema 값과 정확히 같아야 한다. 형식이 틀리면
 * {@code INVALID_RESPONSE}이고 원문은 예외에 담지 않는다.
 */
public class QuizJudgeResponseParser {

    private static final Set<String> ROOT_FIELDS = Set.of("questions");
    private static final Set<String> RESULT_FIELDS = Set.of("verdict", "reason");
    private static final Set<String> QUESTION_FIELDS = questionFields();

    private final ObjectReader reader;

    public QuizJudgeResponseParser(ObjectMapper objectMapper) {
        this.reader = objectMapper.reader()
                .with(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    /** 응답 순서 그대로의 문항 평가. */
    public List<QuizJudgeEvaluation.QuestionEvaluation> parse(String json) {
        JsonNode root;
        try {
            root = reader.readTree(json == null ? "" : json);
        } catch (JacksonException exception) {
            throw invalid();
        }
        if (root == null || !root.isObject() || !fieldNames(root).equals(ROOT_FIELDS)
                || !root.get("questions").isArray()) {
            throw invalid();
        }
        List<QuizJudgeEvaluation.QuestionEvaluation> questions = new ArrayList<>();
        for (JsonNode question : root.get("questions")) {
            if (!question.isObject() || !fieldNames(question).equals(QUESTION_FIELDS)) {
                throw invalid();
            }
            JsonNode questionNo = question.get("questionNo");
            if (!questionNo.isIntegralNumber() || !questionNo.canConvertToInt()) {
                throw invalid();
            }
            Map<JudgeRubric, QuizJudgeEvaluation.RubricResult> items = new EnumMap<>(JudgeRubric.class);
            for (JudgeRubric rubric : JudgeRubric.values()) {
                items.put(rubric, rubricResult(question.get(rubric.field())));
            }
            questions.add(new QuizJudgeEvaluation.QuestionEvaluation(questionNo.intValue(), items));
        }
        return questions;
    }

    private QuizJudgeEvaluation.RubricResult rubricResult(JsonNode node) {
        if (!node.isObject() || !fieldNames(node).equals(RESULT_FIELDS)
                || !node.get("verdict").isString() || !node.get("reason").isString()) {
            throw invalid();
        }
        JudgeVerdict verdict = JudgeVerdict.fromValue(node.get("verdict").asString());
        if (verdict == null) {
            throw invalid();
        }
        return new QuizJudgeEvaluation.RubricResult(verdict, node.get("reason").asString());
    }

    private static Set<String> fieldNames(JsonNode node) {
        return node.properties().stream().map(Map.Entry::getKey).collect(Collectors.toSet());
    }

    private static Set<String> questionFields() {
        Set<String> fields = new LinkedHashSet<>();
        fields.add("questionNo");
        for (JudgeRubric rubric : JudgeRubric.values()) {
            fields.add(rubric.field());
        }
        return Set.copyOf(fields);
    }

    private static QuizJudgeException invalid() {
        return new QuizJudgeException(QuizJudgeException.Failure.INVALID_RESPONSE, null);
    }
}
