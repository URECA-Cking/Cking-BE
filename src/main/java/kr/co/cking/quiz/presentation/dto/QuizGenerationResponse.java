package kr.co.cking.quiz.presentation.dto;

import kr.co.cking.quiz.application.dto.QuizGenerationView;
import kr.co.cking.quiz.domain.QuizGenerationFailure;
import kr.co.cking.quiz.domain.QuizGenerationStatus;

import java.time.Instant;

/** Quiz 생성 작업 상태. 생성된 문항은 Creator Quiz 상세 API로 조회한다. */
public record QuizGenerationResponse(
        Long generationId,
        QuizGenerationStatus status,
        String videoId,
        String title,
        int questionCount,
        int optionCount,
        Long quizId,
        Long revisionId,
        QuizGenerationFailure failureCode,
        Instant requestedAt,
        Instant startedAt,
        Instant completedAt
) {

    public static QuizGenerationResponse from(QuizGenerationView view) {
        return new QuizGenerationResponse(view.generationId(), view.status(), view.videoId(), view.title(),
                view.questionCount(), view.optionCount(), view.quizId(), view.revisionId(), view.failureCode(),
                view.requestedAt(), view.startedAt(), view.completedAt());
    }
}
