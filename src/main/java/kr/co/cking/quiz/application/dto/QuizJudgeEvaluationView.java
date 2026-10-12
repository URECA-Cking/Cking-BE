package kr.co.cking.quiz.application.dto;

import kr.co.cking.quiz.domain.QuizJudgeFailure;
import kr.co.cking.quiz.domain.QuizJudgeJob;
import kr.co.cking.quiz.domain.QuizJudgeJobStatus;
import kr.co.cking.quiz.domain.QuizJudgeSeries;
import kr.co.cking.quiz.domain.QuizJudgeStoredResult;
import kr.co.cking.quiz.judge.JudgeAggregation;

import java.time.Instant;
import java.util.List;
import kr.co.cking.quiz.judge.QuizJudgeEvaluation;

/**
 * 평가 Job 상태(이슈 #529). {@code evaluationId}는 Job ID이고, {@code resultId}는 SUCCEEDED일 때만 있는 불변 결과
 * ID다. Creator 전용이며 공개 응답으로 내보내지 않는다.
 *
 * @param aggregation 판정 요약. SUCCEEDED일 때만 있다. ALL_PASS여도 승인은 Creator가 따로 한다
 */
public record QuizJudgeEvaluationView(
        Long evaluationId,
        Long quizId,
        Long revisionId,
        QuizJudgeJobStatus status,
        int attempt,
        int maxAttempts,
        QuizJudgeFailure failureCode,
        Long resultId,
        JudgeAggregation aggregation,
        Instant requestedAt,
        Instant startedAt,
        Instant completedAt,
        List<QuizJudgeEvaluation.QuestionEvaluation> questions
) {

    /** @param result SUCCEEDED Job의 결과. 없으면 null */
    public static QuizJudgeEvaluationView of(QuizJudgeJob job, QuizJudgeStoredResult result) {
        return of(job, result, List.of());
    }

    public static QuizJudgeEvaluationView of(QuizJudgeJob job, QuizJudgeStoredResult result,
                                            List<QuizJudgeEvaluation.QuestionEvaluation> questions) {
        return new QuizJudgeEvaluationView(job.getJobId(), job.getQuizId(), job.getRevisionId(), job.getStatus(),
                job.getAttemptNo(), QuizJudgeSeries.MAX_ATTEMPTS, job.getFailureCode(),
                result == null ? null : result.getResultId(), result == null ? null : result.getAggregation(),
                job.getRequestedAt(), job.getStartedAt(), job.getCompletedAt(), List.copyOf(questions));
    }
}
