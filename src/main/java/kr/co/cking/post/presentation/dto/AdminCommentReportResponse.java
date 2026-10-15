package kr.co.cking.post.presentation.dto;

import kr.co.cking.post.application.dto.AdminCommentReportView;
import kr.co.cking.post.domain.CommentReportReason;
import kr.co.cking.post.domain.CommentReportStatus;

import java.time.Instant;
import java.util.Map;

/**
 * 관리자 신고 목록의 한 행.
 *
 * @param blocked           필터가 가장 최근에 차단한 댓글이면 true
 * @param hiddenByAdmin     관리자가 숨긴 댓글이면 true
 * @param status            댓글 단위 처리 상태(PENDING·RESOLVED·REJECTED)
 * @param reportCount       처리 여부와 관계없는 전체 신고 수
 * @param pendingReportCount 그중 접수(PENDING) 상태인 신고 수
 * @param reasonCounts      신고된 사유별 신고 수. 신고가 없는 사유는 포함하지 않는다
 */
public record AdminCommentReportResponse(
        Long commentId,
        Long postId,
        Long creatorId,
        Long authorMemberId,
        String content,
        boolean blocked,
        boolean hiddenByAdmin,
        CommentReportStatus status,
        long reportCount,
        long pendingReportCount,
        Map<CommentReportReason, Long> reasonCounts,
        Instant latestReportedAt
) {

    public static AdminCommentReportResponse from(AdminCommentReportView view) {
        return new AdminCommentReportResponse(
                view.commentId(),
                view.postId(),
                view.creatorId(),
                view.authorMemberId(),
                view.content(),
                view.blocked(),
                view.hiddenByAdmin(),
                view.status(),
                view.reportCount(),
                view.pendingReportCount(),
                view.reasonCounts(),
                view.latestReportedAt());
    }
}
