package kr.co.cking.quiz.application.dto;

import kr.co.cking.quiz.domain.QuizReviewStatus;
import kr.co.cking.quiz.domain.QuizRevisionDecision;

import java.time.Instant;

/** revision에 기록된 Creator 결정. */
public record QuizDecisionView(
        Long quizId,
        Long revisionId,
        QuizReviewStatus reviewStatus,
        String reason,
        Instant decidedAt
) {

    public static QuizDecisionView of(Long quizId, QuizRevisionDecision decision) {
        return new QuizDecisionView(quizId, decision.getRevisionId(), QuizReviewStatus.of(decision),
                decision.getReason(), decision.getDecidedAt());
    }
}
