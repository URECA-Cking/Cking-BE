package kr.co.cking.quiz.judge;

import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 검증을 통과한 Pointwise 평가. 문항은 {@code questionNo} 오름차순이고 문항마다 7개 rubric 항목이 모두 있다.
 * fail·uncertain도 유효한 평가이며 그대로 보존한다.
 */
public record QuizJudgeEvaluation(List<QuestionEvaluation> questions) {

    public QuizJudgeEvaluation {
        if (questions == null || questions.isEmpty()) {
            throw new IllegalArgumentException("평가 문항이 없습니다.");
        }
        questions = List.copyOf(questions);
    }

    public JudgeAggregation aggregation() {
        return JudgeAggregation.of(questions.stream()
                .flatMap(question -> question.items().values().stream())
                .map(RubricResult::verdict)
                .toList());
    }

    public record QuestionEvaluation(int questionNo, Map<JudgeRubric, RubricResult> items) {

        public QuestionEvaluation {
            if (items == null || items.size() != JudgeRubric.values().length) {
                throw new IllegalArgumentException("문항마다 rubric 항목 " + JudgeRubric.values().length + "개가 필요합니다.");
            }
            items = Collections.unmodifiableMap(new EnumMap<>(items));
        }

        public RubricResult item(JudgeRubric rubric) {
            return items.get(rubric);
        }
    }

    public record RubricResult(JudgeVerdict verdict, String reason) {

        public RubricResult {
            if (verdict == null || reason == null) {
                throw new IllegalArgumentException("판정과 근거가 필요합니다.");
            }
        }
    }
}
