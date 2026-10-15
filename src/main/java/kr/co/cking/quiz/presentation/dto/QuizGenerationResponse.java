package kr.co.cking.quiz.presentation.dto;

import kr.co.cking.quiz.application.dto.QuizGenerationView;
import kr.co.cking.quiz.domain.QuizGenerationFailure;
import kr.co.cking.quiz.domain.QuizGenerationReviewStage;
import kr.co.cking.quiz.domain.QuizGenerationStatus;

import java.time.Instant;

/**
 * Quiz 생성 작업 상태. 생성된 문항은 Creator Quiz 상세 API로 조회한다.
 *
 * @param reviewStage        원문 검토 작업의 단계. 검토 없이 이어서 생성하는 기존 작업은 null이다
 * @param contentEdited      확정 원문이 AI 원문과 다른지. 확정 전이거나 기존 작업이면 null이다
 * @param contentConfirmedAt 원문을 확정한 시각. 확정 전이거나 기존 작업이면 null이다
 */
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
        Instant completedAt,
        QuizGenerationReviewStage reviewStage,
        Boolean contentEdited,
        Instant contentConfirmedAt
) {

    public static QuizGenerationResponse from(QuizGenerationView view) {
        return new QuizGenerationResponse(view.generationId(), view.status(), view.videoId(), view.title(),
                view.questionCount(), view.optionCount(), view.quizId(), view.revisionId(), view.failureCode(),
                view.requestedAt(), view.startedAt(), view.completedAt(), view.reviewStage(), view.contentEdited(),
                view.contentConfirmedAt());
    }
}
