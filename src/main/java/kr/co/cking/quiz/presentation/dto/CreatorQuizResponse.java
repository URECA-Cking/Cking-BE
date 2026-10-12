package kr.co.cking.quiz.presentation.dto;

import kr.co.cking.quiz.application.dto.CreatorQuizViews;
import kr.co.cking.quiz.application.dto.QuizDecisionView;
import kr.co.cking.quiz.application.dto.QuizPublicationView;
import kr.co.cking.quiz.application.dto.QuizQuestionContent;
import kr.co.cking.quiz.domain.QuizPublicationStatus;
import kr.co.cking.quiz.domain.QuizReviewStatus;

import java.time.Instant;
import java.util.List;
import java.util.stream.IntStream;

/** Creator 본인 API 응답. 정답·해설·근거·원본 텍스트를 담는다. */
public final class CreatorQuizResponse {

    private CreatorQuizResponse() {
    }

    public record Summary(
            Long quizId,
            String title,
            QuizPublicationStatus publicationStatus,
            Long publishedRevisionId,
            Long latestRevisionId,
            int latestRevisionNo,
            QuizReviewStatus latestReviewStatus,
            Instant publishedAt,
            Instant createdAt,
            Instant updatedAt
    ) {

        public static Summary from(CreatorQuizViews.Summary view) {
            return new Summary(view.quizId(), view.title(), view.publicationStatus(), view.publishedRevisionId(),
                    view.latestRevisionId(), view.latestRevisionNo(), view.latestReviewStatus(), view.publishedAt(),
                    view.createdAt(), view.updatedAt());
        }
    }

    public record Detail(
            Long quizId,
            Long creatorId,
            String videoId,
            QuizPublicationStatus publicationStatus,
            Long publishedRevisionId,
            Instant publishedAt,
            Instant unpublishedAt,
            Instant createdAt,
            Instant updatedAt,
            List<RevisionSummary> revisions
    ) {

        public static Detail from(CreatorQuizViews.Detail view) {
            return new Detail(view.quizId(), view.creatorId(), view.videoId(), view.publicationStatus(),
                    view.publishedRevisionId(), view.publishedAt(), view.unpublishedAt(), view.createdAt(),
                    view.updatedAt(), view.revisions().stream().map(RevisionSummary::from).toList());
        }
    }

    public record RevisionSummary(
            Long revisionId,
            int revisionNo,
            String title,
            QuizReviewStatus reviewStatus,
            Instant decidedAt,
            Instant createdAt
    ) {

        static RevisionSummary from(CreatorQuizViews.RevisionSummary view) {
            return new RevisionSummary(view.revisionId(), view.revisionNo(), view.title(), view.reviewStatus(),
                    view.decidedAt(), view.createdAt());
        }
    }

    public record Revision(
            Long quizId,
            Long revisionId,
            int revisionNo,
            boolean latest,
            boolean published,
            String title,
            String videoId,
            String promptVersion,
            String contentText,
            QuizReviewStatus reviewStatus,
            String decisionReason,
            Instant decidedAt,
            Instant createdAt,
            List<Question> questions
    ) {

        public static Revision from(CreatorQuizViews.Revision view) {
            return new Revision(view.quizId(), view.revisionId(), view.revisionNo(), view.latest(), view.published(),
                    view.title(), view.videoId(), view.promptVersion(), view.contentText(), view.reviewStatus(),
                    view.decisionReason(), view.decidedAt(), view.createdAt(),
                    view.questions().stream().map(Question::from).toList());
        }
    }

    /** @param correctOptionIndex 0-based로, 정답 보기의 {@code optionNo}와 같다 */
    public record Question(
            int questionNo,
            String question,
            List<Option> options,
            int correctOptionIndex,
            String explanation,
            String sourceEvidence
    ) {

        static Question from(QuizQuestionContent content) {
            return new Question(content.questionNo(), content.question(), Option.listOf(content.options()),
                    content.correctOptionIndex(), content.explanation(), content.sourceEvidence());
        }
    }

    /** @param optionNo 0-based 표시 순서 */
    public record Option(int optionNo, String text) {

        static List<Option> listOf(List<String> options) {
            return IntStream.range(0, options.size()).mapToObj(index -> new Option(index, options.get(index))).toList();
        }
    }

    public record Decision(Long quizId, Long revisionId, QuizReviewStatus reviewStatus, String reason, Instant decidedAt) {

        public static Decision from(QuizDecisionView view) {
            return new Decision(view.quizId(), view.revisionId(), view.reviewStatus(), view.reason(), view.decidedAt());
        }
    }

    public record Publication(
            Long quizId,
            QuizPublicationStatus publicationStatus,
            Long publishedRevisionId,
            Instant publishedAt,
            Instant unpublishedAt
    ) {

        public static Publication from(QuizPublicationView view) {
            return new Publication(view.quizId(), view.publicationStatus(), view.publishedRevisionId(),
                    view.publishedAt(), view.unpublishedAt());
        }
    }
}
