package kr.co.cking.quiz.domain;

/** revision의 검토 상태. 결정 기록이 없으면 {@link #PENDING_REVIEW}다. */
public enum QuizReviewStatus {
    PENDING_REVIEW,
    APPROVED,
    REJECTED;

    public static QuizReviewStatus of(QuizRevisionDecision decision) {
        if (decision == null) {
            return PENDING_REVIEW;
        }
        return decision.getDecision() == QuizRevisionDecisionType.APPROVED ? APPROVED : REJECTED;
    }
}
