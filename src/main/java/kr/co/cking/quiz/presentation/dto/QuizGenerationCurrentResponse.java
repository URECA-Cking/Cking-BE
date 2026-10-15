package kr.co.cking.quiz.presentation.dto;

import kr.co.cking.quiz.application.dto.QuizGenerationCurrentView;
import kr.co.cking.quiz.application.dto.QuizGenerationView;
import kr.co.cking.quiz.domain.QuizGenerationFailure;
import kr.co.cking.quiz.domain.QuizGenerationReviewStage;
import kr.co.cking.quiz.domain.QuizGenerationStatus;
import kr.co.cking.quiz.grounding.EvidenceType;

import java.time.Instant;
import java.util.List;

/**
 * Creator의 진행 중인 Quiz 생성 작업(이슈 #615). 생성 작업 상태 필드는 {@link QuizGenerationResponse}와 같고, 원문 검토
 * 대기일 때만 {@code content}가 있다.
 */
public record QuizGenerationCurrentResponse(
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
        Instant contentConfirmedAt,
        Content content
) {

    public static QuizGenerationCurrentResponse from(QuizGenerationCurrentView current) {
        QuizGenerationView view = current.generation();
        return new QuizGenerationCurrentResponse(view.generationId(), view.status(), view.videoId(), view.title(),
                view.questionCount(), view.optionCount(), view.quizId(), view.revisionId(), view.failureCode(),
                view.requestedAt(), view.startedAt(), view.completedAt(), view.reviewStage(), view.contentEdited(),
                view.contentConfirmedAt(), current.content() == null ? null : Content.from(current.content()));
    }

    /**
     * Creator가 검토할 Grounding 결과. facts는 AI가 만든 근거라 실제 영상과 다를 수 있다.
     *
     * @param maxLength 확정할 수 있는 원문의 최대 길이
     */
    public record Content(String aiContentText, List<Fact> facts, String groundingModel,
                          String groundingPromptVersion, int maxLength) {

        static Content from(QuizGenerationCurrentView.Content content) {
            return new Content(content.aiContentText(), content.facts().stream()
                    .map(fact -> new Fact(fact.fact(), fact.evidenceType(), fact.evidence(), fact.timestampStart(),
                            fact.timestampEnd()))
                    .toList(), content.groundingModel(), content.groundingPromptVersion(), content.maxLength());
        }
    }

    /** 영상에서 확인한 사실 하나와 근거. 시각은 MM:SS 형식이며 없을 수 있다. */
    public record Fact(String fact, EvidenceType evidenceType, String evidence, String timestampStart,
                       String timestampEnd) {
    }
}
