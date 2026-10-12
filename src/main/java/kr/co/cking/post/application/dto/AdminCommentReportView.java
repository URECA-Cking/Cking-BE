package kr.co.cking.post.application.dto;

import kr.co.cking.post.domain.CommentReportReason;
import kr.co.cking.post.domain.CommentReportStatus;

import java.time.Instant;
import java.util.Map;

/**
 * 관리자 신고 목록의 한 행. 신고된 댓글과 신고를 모은 값이다.
 *
 * @param blocked           필터가 가장 최근에 차단(BLOCK)한 댓글이면 true
 * @param hiddenByAdmin     관리자가 숨긴 댓글이면 true
 * @param status            댓글 단위 처리 상태. 접수 신고가 남아 있으면 PENDING, 모두 처리됐고 숨겼으면 RESOLVED, 숨기지
 *                          않았으면 REJECTED
 * @param reportCount       처리 여부와 관계없는 전체 신고 수
 * @param pendingReportCount 그중 접수(PENDING) 상태인 신고 수
 * @param reasonCounts      신고된 사유별 신고 수(처리된 신고 포함). 신고가 없는 사유는 담지 않는다
 */
public record AdminCommentReportView(
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
}
