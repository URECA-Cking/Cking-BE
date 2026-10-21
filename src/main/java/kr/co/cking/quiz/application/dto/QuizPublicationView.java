package kr.co.cking.quiz.application.dto;

import kr.co.cking.quiz.domain.Quiz;
import kr.co.cking.quiz.domain.QuizPublicationStatus;

import java.time.Instant;

/**
 * Quiz의 공개 상태와 게시 revision.
 *
 * @param publishedRevisionId 게시 중인 revision. CLOSED에서는 마감 전 포인터를 보존하므로 공개 여부는 상태로 판단한다.
 * @param closedAt 최초 수동 마감 시각. 마감 전에는 null
 * @param publishedAt   현재(게시 취소 상태라면 마지막) 게시가 시작된 시각
 * @param unpublishedAt 그 게시가 취소된 시각. {@code PUBLISHED}이면 null
 */
public record QuizPublicationView(
        Long quizId,
        QuizPublicationStatus publicationStatus,
        Long publishedRevisionId,
        Instant publishedAt,
        Instant unpublishedAt,
        Instant closedAt
) {

    public static QuizPublicationView of(Quiz quiz) {
        return new QuizPublicationView(quiz.getQuizId(), quiz.getPublicationStatus(), quiz.getPublishedRevisionId(),
                quiz.getPublishedAt(), quiz.getUnpublishedAt(), quiz.getClosedAt());
    }
}
