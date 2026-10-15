package kr.co.cking.quiz.judge;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QuizJudgeEvaluationValidatorTest {

    private final QuizJudgeResponseParser parser = new QuizJudgeResponseParser(new ObjectMapper());
    private final QuizJudgeEvaluationValidator validator = new QuizJudgeEvaluationValidator();

    @Test
    void 모든_문항을_한_번씩_평가했으면_번호_순으로_정렬한다() {
        String json = QuizJudgeFixtures.json(Map.of("questions", List.of(
                QuizJudgeFixtures.question(2, (no, rubric) -> new String[] {"pass", "근거"}),
                QuizJudgeFixtures.question(0, (no, rubric) -> new String[] {"pass", "근거"}),
                QuizJudgeFixtures.question(1, (no, rubric) -> new String[] {"pass", "근거"}))));

        QuizJudgeEvaluation evaluation = validator.validate(parser.parse(json), 3, 200);

        assertThat(evaluation.questions()).extracting(QuizJudgeEvaluation.QuestionEvaluation::questionNo)
                .containsExactly(0, 1, 2);
        assertThat(evaluation.aggregation()).isEqualTo(JudgeAggregation.ALL_PASS);
    }

    @Test
    void fail과_uncertain도_유효한_평가로_통과하고_요약은_fail이_우선이다() {
        String uncertain = QuizJudgeFixtures.output(3, (no, rubric) -> no == 2 && rubric == JudgeRubric.KOREAN_QUALITY
                ? new String[] {"uncertain", "판단하기 어렵습니다."} : new String[] {"pass", "근거"});
        String failAndUncertain = QuizJudgeFixtures.output(3, (no, rubric) -> no == 0
                ? new String[] {rubric == JudgeRubric.TEXT_FAITHFULNESS ? "fail" : "uncertain", "근거"}
                : new String[] {"pass", "근거"});

        assertThat(validator.validate(parser.parse(uncertain), 3, 200).aggregation())
                .isEqualTo(JudgeAggregation.UNCERTAIN_ONLY);
        assertThat(validator.validate(parser.parse(failAndUncertain), 3, 200).aggregation())
                .isEqualTo(JudgeAggregation.HAS_FAIL);
    }

    @Test
    void 문항이_빠지거나_겹치거나_범위를_벗어나면_거부한다() {
        String[] invalid = {
                QuizJudgeFixtures.allPass(2),
                QuizJudgeFixtures.allPass(4),
                QuizJudgeFixtures.json(Map.of("questions", List.of(
                        QuizJudgeFixtures.question(0, (no, rubric) -> new String[] {"pass", "근거"}),
                        QuizJudgeFixtures.question(0, (no, rubric) -> new String[] {"pass", "근거"}),
                        QuizJudgeFixtures.question(1, (no, rubric) -> new String[] {"pass", "근거"})))),
                QuizJudgeFixtures.json(Map.of("questions", List.of(
                        QuizJudgeFixtures.question(1, (no, rubric) -> new String[] {"pass", "근거"}),
                        QuizJudgeFixtures.question(2, (no, rubric) -> new String[] {"pass", "근거"}),
                        QuizJudgeFixtures.question(3, (no, rubric) -> new String[] {"pass", "근거"})))),
                QuizJudgeFixtures.json(Map.of("questions", List.of(
                        QuizJudgeFixtures.question(-1, (no, rubric) -> new String[] {"pass", "근거"}),
                        QuizJudgeFixtures.question(0, (no, rubric) -> new String[] {"pass", "근거"}),
                        QuizJudgeFixtures.question(1, (no, rubric) -> new String[] {"pass", "근거"}))))
        };
        for (String json : invalid) {
            assertInvalid(() -> validator.validate(parser.parse(json), 3, 200));
        }
    }

    @Test
    void reason은_공백이면_안_되고_길이_상한을_넘으면_자르지_않고_거부한다() {
        String exactly = "가".repeat(200);
        assertThat(validator.validate(parser.parse(withReason(exactly)), 3, 200)
                .questions().get(1).item(JudgeRubric.QUESTION_CLARITY).reason()).isEqualTo(exactly);

        for (String reason : new String[] {"", " ", "\n\t", "가".repeat(201)}) {
            assertInvalid(() -> validator.validate(parser.parse(withReason(reason)), 3, 200));
        }
    }

    private String withReason(String reason) {
        return QuizJudgeFixtures.output(3, (no, rubric) -> no == 1 && rubric == JudgeRubric.QUESTION_CLARITY
                ? new String[] {"pass", reason} : new String[] {"pass", "근거"});
    }

    @Test
    void 조작된_한도_300도_정책의_200자_상한을_늘리지_못한다() {
        String accepted = "가".repeat(200);
        assertThat(validator.validate(parser.parse(withReason(accepted)), 3, 300).questions().get(1)
                .item(JudgeRubric.QUESTION_CLARITY).reason()).isEqualTo(accepted);
        for (String reason : new String[]{"가".repeat(201), "가".repeat(300), " \t\n"}) {
            assertInvalid(() -> validator.validate(parser.parse(withReason(reason)), 3, 300));
        }
    }

    private void assertInvalid(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(QuizJudgeException.class, exception ->
                assertThat(exception.getFailure()).isEqualTo(QuizJudgeException.Failure.INVALID_RESPONSE));
    }
}
