package kr.co.cking.post.application.dto;

import kr.co.cking.post.domain.CommentReportReason;

import java.time.Instant;
import java.util.Map;

/**
 * 관리자 신고 목록의 한 행. 신고된 댓글과 신고를 모은 값이다.
 *
 * @param blocked      필터가 가장 최근에 차단(BLOCK)한 댓글이면 true
 * @param reasonCounts 신고된 사유별 신고 수. 신고가 없는 사유는 담지 않는다
 */
public record AdminCommentReportView(
        Long commentId,
        Long postId,
        Long creatorId,
        Long authorMemberId,
        String content,
        boolean blocked,
        long reportCount,
        Map<CommentReportReason, Long> reasonCounts,
        Instant latestReportedAt
) {
}
