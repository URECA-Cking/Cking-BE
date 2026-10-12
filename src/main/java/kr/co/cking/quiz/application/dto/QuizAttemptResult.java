package kr.co.cking.quiz.application.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 본인 응시 시점의 결과. 현재 게시 revision이나 Ticket 실행 상태를 참조하지 않는다. */
public record QuizAttemptResult(Long attemptId, UUID requestId, Long creatorId, Long quizId, Long revisionId,
        String title, String videoId, int questionCount, int correctCount, Instant submittedAt,
        String revisionSha256, String snapshotSha256, List<Question> questions, Reward reward) {
    public QuizAttemptResult { questions = List.copyOf(questions); }
    public record Question(int questionNo, String question, List<String> options, int selectedOptionIndex,
            int correctOptionIndex, boolean correct, String explanation, String sourceEvidence) {
        public Question { options = List.copyOf(options); }
    }
    public record Reward(long amount, UUID requestId, String status) {}
}
