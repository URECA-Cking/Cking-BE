package kr.co.cking.quiz.judge;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QuizJudgeResponseParserTest {

    private final QuizJudgeResponseParser parser = new QuizJudgeResponseParser(new ObjectMapper());

    @Test
    void 문항마다_7개_항목의_판정과_근거를_읽는다() {
        String json = QuizJudgeFixtures.output(3, (no, rubric) -> rubric == JudgeRubric.UNIQUE_ANSWER && no == 1
                ? new String[] {"uncertain", "판단 근거가 부족합니다."}
                : new String[] {"pass", "문제없습니다."});

        List<QuizJudgeEvaluation.QuestionEvaluation> questions = parser.parse(json);

        assertThat(questions).extracting(QuizJudgeEvaluation.QuestionEvaluation::questionNo).containsExactly(0, 1, 2);
        assertThat(questions.get(1).item(JudgeRubric.UNIQUE_ANSWER))
                .isEqualTo(new QuizJudgeEvaluation.RubricResult(JudgeVerdict.UNCERTAIN, "판단 근거가 부족합니다."));
        assertThat(questions.get(0).items()).hasSize(7);
    }

    @Test
    void 형식이_틀리면_원문_없이_INVALID_RESPONSE다() {
        Map<String, Object> valid = QuizJudgeFixtures.question(0, (no, rubric) -> new String[] {"pass", "근거"});
        List<String> invalid = new ArrayList<>(List.of(
                "",
                "not json",
                "[]",
                "{}",
                "{\"questions\": {}}",
                "{\"questions\": [], \"extra\": 1}",
                "{\"questions\": [1]}",
                QuizJudgeFixtures.allPass(1) + " {}",
                "{\"questions\": [], \"questions\": []}"));
        invalid.add(withQuestion(valid, "questionNo", "0"));
        invalid.add(withQuestion(valid, "questionNo", 0.0));
        invalid.add(withQuestion(valid, "questionNo", 1.5));
        invalid.add(withQuestion(valid, "questionNo", 3_000_000_000L));
        invalid.add(withQuestion(valid, "extra", "x"));
        invalid.add(withoutQuestionField(valid, "textFaithfulness"));
        invalid.add(withQuestion(valid, "uniqueAnswer", Map.of("verdict", "PASS", "reason", "근거")));
        invalid.add(withQuestion(valid, "uniqueAnswer", Map.of("verdict", "unknown", "reason", "근거")));
        invalid.add(withQuestion(valid, "uniqueAnswer", Map.of("verdict", "pass")));
        invalid.add(withQuestion(valid, "uniqueAnswer", Map.of("verdict", "pass", "reason", 1)));
        invalid.add(withQuestion(valid, "uniqueAnswer", Map.of("verdict", "pass", "reason", "근거", "score", 1)));
        invalid.add(withQuestion(valid, "uniqueAnswer", "pass"));

        for (String json : invalid) {
            assertThatThrownBy(() -> parser.parse(json)).as(json)
                    .isInstanceOfSatisfying(QuizJudgeException.class, exception -> {
                        assertThat(exception.getFailure()).isEqualTo(QuizJudgeException.Failure.INVALID_RESPONSE);
                        assertThat(exception).hasNoCause();
                        assertThat(exception.getMessage()).doesNotContain("근거");
                    });
        }
    }

    @Test
    void 문항_안에서_같은_키가_두_번_나오면_거부한다() {
        String json = QuizJudgeFixtures.allPass(1).replace("{\"questionNo\":0,", "{\"questionNo\":0,\"questionNo\":0,");

        assertThatThrownBy(() -> parser.parse(json)).isInstanceOf(QuizJudgeException.class);
    }

    private String withQuestion(Map<String, Object> question, String field, Object value) {
        Map<String, Object> changed = new LinkedHashMap<>(question);
        changed.put(field, value);
        return QuizJudgeFixtures.json(Map.of("questions", List.of(changed)));
    }

    private String withoutQuestionField(Map<String, Object> question, String field) {
        Map<String, Object> changed = new LinkedHashMap<>(question);
        changed.remove(field);
        return QuizJudgeFixtures.json(Map.of("questions", List.of(changed)));
    }
}
