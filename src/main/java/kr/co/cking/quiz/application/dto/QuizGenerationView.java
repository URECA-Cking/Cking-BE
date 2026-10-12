package kr.co.cking.quiz.application.dto;

import kr.co.cking.quiz.domain.QuizGeneration;
import kr.co.cking.quiz.domain.QuizGenerationFailure;
import kr.co.cking.quiz.domain.QuizGenerationStatus;

import java.time.Instant;

/**
 * Quiz 생성 작업 상태. Grounding 결과·생성 문항·Provider 오류 원문은 담지 않는다.
 *
 * @param quizId      SUCCEEDED일 때만 있다
 * @param failureCode FAILED·UNKNOWN일 때만 있다
 */
public record QuizGenerationView(
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

    public static QuizGenerationView of(QuizGeneration generation) {
        return new QuizGenerationView(generation.getGenerationId(), generation.getStatus(), generation.getVideoId(),
                generation.getTitle(), generation.getQuestionCount(), generation.getOptionCount(),
                generation.getQuizId(), generation.getRevisionId(), generation.getFailureCode(),
                generation.getRequestedAt(), generation.getStartedAt(), generation.getCompletedAt());
    }
}
