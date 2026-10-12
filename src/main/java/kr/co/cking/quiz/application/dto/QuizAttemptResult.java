package kr.co.cking.quiz.application.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 본인 응시 시점의 결과. 현재 게시 revision을 참조하지 않는다. Ticket 지급 상태는 응답 시점에 따로 합성한다. */
public record QuizAttemptResult(Long attemptId, UUID requestId, Long creatorId, Long quizId, Long revisionId,
        String title, String videoId, int questionCount, int correctCount, Instant submittedAt,
        String revisionSha256, String snapshotSha256, List<Question> questions, Reward reward) {
    public QuizAttemptResult { questions = List.copyOf(questions); }
    public record Question(int questionNo, String question, List<String> options, int selectedOptionIndex,
            int correctOptionIndex, boolean correct, String explanation, String sourceEvidence) {
        public Question { options = List.copyOf(options); }
    }

    /**
     * @param status        저장 당시 값(NOT_INTEGRATED·NOT_ELIGIBLE). 스냅샷 hash에 포함되며 바뀌지 않는다
     * @param paymentStatus 응답 시점의 실제 Ticket 지급 상태. 스냅샷·hash에 포함하지 않는다. 저장된 결과에는 없다
     */
    public record Reward(long amount, UUID requestId, String status, QuizRewardPaymentStatus paymentStatus) {
        public Reward(long amount, UUID requestId, String status) { this(amount, requestId, status, null); }
    }

    /** 검증된 결과에 지급 상태만 덧붙인 응답. 저장값·hash는 그대로다. */
    public QuizAttemptResult withPaymentStatus(QuizRewardPaymentStatus paymentStatus) {
        return new QuizAttemptResult(attemptId, requestId, creatorId, quizId, revisionId, title, videoId, questionCount,
                correctCount, submittedAt, revisionSha256, snapshotSha256, questions,
                new Reward(reward.amount(), reward.requestId(), reward.status(), paymentStatus));
    }
}
