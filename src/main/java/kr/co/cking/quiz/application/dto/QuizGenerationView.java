package kr.co.cking.quiz.application.dto;

import kr.co.cking.quiz.domain.QuizGeneration;
import kr.co.cking.quiz.domain.QuizGenerationFailure;
import kr.co.cking.quiz.domain.QuizGenerationReviewStage;
import kr.co.cking.quiz.domain.QuizGenerationStatus;

import java.time.Instant;

/**
 * Quiz 생성 작업 상태. Grounding 결과·생성 문항·Provider 오류 원문은 담지 않는다.
 *
 * @param quizId             SUCCEEDED일 때만 있다
 * @param failureCode        FAILED·UNKNOWN일 때만 있다
 * @param reviewStage        원문 검토 작업의 단계(이슈 #604). 검토 없이 이어서 생성하는 기존 작업은 null이다
 * @param contentEdited      확정 원문이 AI 원문과 다른지. 원문을 확정하기 전이거나 기존 작업이면 null이다
 * @param contentConfirmedAt 원문을 확정한 시각. 확정 전이거나 기존 작업이면 null이다
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
        Instant completedAt,
        QuizGenerationReviewStage reviewStage,
        Boolean contentEdited,
        Instant contentConfirmedAt
) {

    /** 원문 검토 정보가 없는 작업 상태. */
    public QuizGenerationView(Long generationId, QuizGenerationStatus status, String videoId, String title,
                              int questionCount, int optionCount, Long quizId, Long revisionId,
                              QuizGenerationFailure failureCode, Instant requestedAt, Instant startedAt,
                              Instant completedAt) {
        this(generationId, status, videoId, title, questionCount, optionCount, quizId, revisionId, failureCode,
                requestedAt, startedAt, completedAt, null, null, null);
    }

    public static QuizGenerationView of(QuizGeneration generation) {
        String reviewed = generation.getReviewedContentText();
        return new QuizGenerationView(generation.getGenerationId(), generation.getStatus(), generation.getVideoId(),
                generation.getTitle(), generation.getQuestionCount(), generation.getOptionCount(),
                generation.getQuizId(), generation.getRevisionId(), generation.getFailureCode(),
                generation.getRequestedAt(), generation.getStartedAt(), generation.getCompletedAt(),
                generation.getReviewStage(), reviewed == null ? null : !reviewed.equals(generation.getAiContentText()),
                generation.getContentConfirmedAt());
    }
}
