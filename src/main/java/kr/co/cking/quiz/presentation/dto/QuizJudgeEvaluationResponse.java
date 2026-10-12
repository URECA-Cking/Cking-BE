package kr.co.cking.quiz.presentation.dto;

import kr.co.cking.quiz.application.dto.QuizJudgeEvaluationView;
import kr.co.cking.quiz.domain.QuizJudgeFailure;
import kr.co.cking.quiz.domain.QuizJudgeJobStatus;
import kr.co.cking.quiz.judge.JudgeAggregation;
import kr.co.cking.quiz.judge.JudgeRubric;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Creator 전용 상태·판정 응답. Provider 실행 정보와 입력 본문은 포함하지 않는다. */
public record QuizJudgeEvaluationResponse(Long evaluationId, Long quizId, Long revisionId,
        QuizJudgeJobStatus status, int attempt, int maxAttempts, QuizJudgeFailure failureCode, Long resultId,
        JudgeAggregation aggregation, Instant requestedAt, Instant startedAt, Instant completedAt,
        List<Question> questions) {
    public static QuizJudgeEvaluationResponse from(QuizJudgeEvaluationView view) {
        List<Question> questions = view.questions().stream().map(question -> {
            Map<String, Item> items = new LinkedHashMap<>();
            for (JudgeRubric rubric : JudgeRubric.values()) {
                var item = question.item(rubric);
                items.put(rubric.field(), new Item(item.verdict().value(), item.reason()));
            }
            return new Question(question.questionNo(), Collections.unmodifiableMap(items));
        }).toList();
        return new QuizJudgeEvaluationResponse(view.evaluationId(), view.quizId(), view.revisionId(), view.status(),
                view.attempt(), view.maxAttempts(), view.failureCode(), view.resultId(), view.aggregation(),
                view.requestedAt(), view.startedAt(), view.completedAt(), questions);
    }
    public record Question(int questionNo, Map<String, Item> items) {}
    public record Item(String verdict, String reason) {}
}
