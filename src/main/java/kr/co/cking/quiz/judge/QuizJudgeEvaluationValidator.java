package kr.co.cking.quiz.judge;

import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 구조를 통과한 평가의 내용 규칙을 검사한다. 판정이 무엇인지는 보지 않으므로 fail·uncertain 결과도 그대로 통과한다.
 * <ul>
 *   <li>입력의 questionNo(0부터 문항 수-1)를 정확히 한 번씩 평가했다. 빠지거나 겹치거나 없는 번호가 있으면 안 된다</li>
 *   <li>문항마다 7개 rubric 항목이 정확히 있다</li>
 *   <li>reason은 공백만 있으면 안 되고 {@code String.length()} 기준 상한 이하다. 자르거나 다듬지 않는다</li>
 * </ul>
 * 어기면 {@code INVALID_RESPONSE}다.
 */
public class QuizJudgeEvaluationValidator {

    /** Judge policy v1의 절대 상한. DB 컬럼 용량이나 저장된 설정으로 늘릴 수 없다. */
    public static final int MAX_REASON_LENGTH = 200;

    private static final Set<JudgeRubric> ALL_RUBRICS = EnumSet.allOf(JudgeRubric.class);

    /** @return questionNo 오름차순으로 정렬한 평가 */
    public QuizJudgeEvaluation validate(List<QuizJudgeEvaluation.QuestionEvaluation> questions, int questionCount,
                                        int reasonMaxLength) {
        if (questions.size() != questionCount) {
            throw invalid();
        }
        Set<Integer> seen = new HashSet<>();
        for (QuizJudgeEvaluation.QuestionEvaluation question : questions) {
            int questionNo = question.questionNo();
            if (questionNo < 0 || questionNo >= questionCount || !seen.add(questionNo)
                    || !question.items().keySet().equals(ALL_RUBRICS)) {
                throw invalid();
            }
            for (QuizJudgeEvaluation.RubricResult result : question.items().values()) {
                if (result.reason().isBlank() || result.reason().length() > reasonMaxLength
                        || result.reason().length() > MAX_REASON_LENGTH) {
                    throw invalid();
                }
            }
        }
        return new QuizJudgeEvaluation(questions.stream()
                .sorted(Comparator.comparingInt(QuizJudgeEvaluation.QuestionEvaluation::questionNo))
                .toList());
    }

    private QuizJudgeException invalid() {
        return new QuizJudgeException(QuizJudgeException.Failure.INVALID_RESPONSE, null);
    }
}
