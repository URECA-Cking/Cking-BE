package kr.co.cking.quiz.presentation.dto;

import kr.co.cking.quiz.application.dto.CreatorQuizViews;
import kr.co.cking.quiz.application.dto.QuizCompositionMetadata;
import kr.co.cking.quiz.domain.QuizRevisionOrigin;
import kr.co.cking.quiz.domain.QuizApprovalPolicyType;
import kr.co.cking.quiz.domain.QuizQuestionChangeType;
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
            Instant updatedAt,
            Instant closedAt
    ) {

        public static Summary from(CreatorQuizViews.Summary view) {
            return new Summary(view.quizId(), view.title(), view.publicationStatus(), view.publishedRevisionId(),
                    view.latestRevisionId(), view.latestRevisionNo(), view.latestReviewStatus(), view.publishedAt(),
                    view.createdAt(), view.updatedAt(), view.closedAt());
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
            List<RevisionSummary> revisions,
            QuizRevisionOrigin origin, Long parentRevisionId, String hashVersion,
            QuizApprovalPolicyType approvalPolicyVersion, boolean contentLocked, boolean canEdit, Instant closedAt
    ) {

        public static Detail from(CreatorQuizViews.Detail view) { return from(view, null); }
        public static Detail from(CreatorQuizViews.Detail view, QuizCompositionMetadata m) {
            return new Detail(view.quizId(), view.creatorId(), view.videoId(), view.publicationStatus(),
                    view.publishedRevisionId(), view.publishedAt(), view.unpublishedAt(), view.createdAt(),
                    view.updatedAt(), view.revisions().stream().map(RevisionSummary::from).toList(),
                    m == null ? null : m.origin(), m == null ? null : m.parentRevisionId(),
                    m == null ? null : m.hashVersion(), m == null ? null : m.approvalPolicyVersion(),
                    m != null && m.contentLocked(), m != null && m.canEdit(), view.closedAt());
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
            List<Question> questions,
            QuizRevisionOrigin origin, Long parentRevisionId, String hashVersion, String revisionSha256,
            QuizApprovalPolicyType approvalPolicyVersion, boolean contentLocked, boolean canEdit,
            int questionCount, int optionCount,
            Boolean contentEdited, Instant contentConfirmedAt
    ) {

        public static Revision from(CreatorQuizViews.Revision view) { return from(view, null); }
        public static Revision from(CreatorQuizViews.Revision view, QuizCompositionMetadata m) {
            return new Revision(view.quizId(), view.revisionId(), view.revisionNo(), view.latest(), view.published(),
                    view.title(), view.videoId(), view.promptVersion(), view.contentText(), view.reviewStatus(),
                    view.decisionReason(), view.decidedAt(), view.createdAt(),
                    view.questions().stream().map(c -> Question.from(c, m == null ? null : m.questions().stream()
                            .filter(q -> q.questionNo() == c.questionNo()).findFirst().orElseThrow())).toList(),
                    m == null ? null : m.origin(), m == null ? null : m.parentRevisionId(), m == null ? null : m.hashVersion(),
                    m == null ? null : m.revisionSha256(), m == null ? null : m.approvalPolicyVersion(),
                    m != null && m.contentLocked(), m != null && m.canEdit(),
                    m == null ? view.questions().size() : m.questionCount(), m == null ? 0 : m.optionCount(),
                    view.contentEdited(), view.contentConfirmedAt());
        }
    }

    /** @param correctOptionIndex 0-based로, 정답 보기의 {@code optionNo}와 같다 */
    public record Question(
            int questionNo,
            String question,
            List<Option> options,
            int correctOptionIndex,
            String explanation,
            String sourceEvidence,
            Long questionId, Long originQuestionId, Long previousQuestionId, QuizQuestionChangeType changeType,
            String evaluationStatus, QuizCompositionMetadata.SourceEvaluation sourceEvaluation
    ) {

        static Question from(QuizQuestionContent content) { return from(content, null); }
        static Question from(QuizQuestionContent content, QuizCompositionMetadata.Question m) {
            return new Question(content.questionNo(), content.question(), Option.listOf(content.options()),
                    content.correctOptionIndex(), content.explanation(), content.sourceEvidence(),
                    m == null ? null : m.questionId(), m == null ? null : m.originQuestionId(),
                    m == null ? null : m.previousQuestionId(), m == null ? null : m.changeType(),
                    m == null ? null : m.evaluationStatus(), m == null ? null : m.sourceEvaluation());
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
            Instant unpublishedAt,
            Instant closedAt
    ) {

        public static Publication from(QuizPublicationView view) {
            return new Publication(view.quizId(), view.publicationStatus(), view.publishedRevisionId(),
                    view.publishedAt(), view.unpublishedAt(), view.closedAt());
        }
    }
}
