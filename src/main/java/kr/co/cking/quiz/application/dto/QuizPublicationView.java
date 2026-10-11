package kr.co.cking.quiz.application.dto;

import kr.co.cking.quiz.domain.Quiz;
import kr.co.cking.quiz.domain.QuizPublicationStatus;

import java.time.Instant;

/** Quiz의 공개 상태와 게시 revision. */
public record QuizPublicationView(
        Long quizId,
        QuizPublicationStatus publicationStatus,
        Long publishedRevisionId,
        Instant publishedAt,
        Instant unpublishedAt
) {

    public static QuizPublicationView of(Quiz quiz) {
        return new QuizPublicationView(quiz.getQuizId(), quiz.getPublicationStatus(), quiz.getPublishedRevisionId(),
                quiz.getPublishedAt(), quiz.getUnpublishedAt());
    }
}
