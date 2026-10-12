package kr.co.cking.quiz.application.dto;

import kr.co.cking.quiz.domain.QuizPublicationStatus;
import kr.co.cking.quiz.domain.QuizReviewStatus;

import java.time.Instant;
import java.util.List;

/** Creator 본인 조회 결과. 정답·해설·근거·원본 텍스트를 담으므로 본인 API에서만 쓴다. */
public final class CreatorQuizViews {

    private CreatorQuizViews() {
    }

    /** 목록 항목. 제목·검토 상태는 최신 revision 기준이다. */
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
    }

    /** revision 번호 오름차순. */
    public record RevisionSummary(
            Long revisionId,
            int revisionNo,
            String title,
            QuizReviewStatus reviewStatus,
            Instant decidedAt,
            Instant createdAt
    ) {
    }

    /** @param decisionReason 거절 사유. 승인·검토 대기면 null */
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
            List<QuizQuestionContent> questions
    ) {
    }
}
