package kr.co.cking.post.presentation.dto;

import kr.co.cking.post.application.dto.AdminCommentReportView;
import kr.co.cking.post.domain.CommentFilterStatus;
import kr.co.cking.post.domain.CommentReportReason;
import kr.co.cking.post.domain.CommentReportStatus;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 관리자 신고 목록의 한 행.
 *
 * @param postPreview       게시글 본문 앞부분
 * @param creatorName       스페이스 Creator의 활동명
 * @param authorName        작성자 표시 이름. Creator 계정은 활동명, 그 밖의 회원은 닉네임이다
 * @param blocked           필터가 가장 최근에 차단한 댓글이면 true
 * @param filterReasons     가장 최근 BLOCK 판정의 사유. 차단되지 않은 댓글은 비어 있다
 * @param filterStatus      판정 상태. 차단된 댓글이 DONE이 아니면 본문을 고쳐 재판정 전이라 사유가 이전 본문의 것이다
 * @param hiddenByAdmin     관리자가 숨긴 댓글이면 true
 * @param status            댓글 단위 처리 상태(PENDING·RESOLVED·REJECTED)
 * @param reportCount       처리 여부와 관계없는 전체 신고 수
 * @param pendingReportCount 그중 접수(PENDING) 상태인 신고 수
 * @param reasonCounts      신고된 사유별 신고 수. 신고가 없는 사유는 포함하지 않는다
 * @param reportDetails     기타 신고 설명. 최근 신고 순이고 신고자는 담지 않는다
 * @param handledBy         가장 최근에 신고를 처리한 관리자 회원 ID. 처리한 적 없으면 null이다
 * @param handledAt         가장 최근 처리 시각. 처리한 적 없으면 null이다
 */
public record AdminCommentReportResponse(
        Long commentId,
        Long postId,
        String postPreview,
        Long creatorId,
        String creatorName,
        Long authorMemberId,
        String authorName,
        String content,
        boolean blocked,
        List<String> filterReasons,
        CommentFilterStatus filterStatus,
        boolean hiddenByAdmin,
        CommentReportStatus status,
        long reportCount,
        long pendingReportCount,
        Map<CommentReportReason, Long> reasonCounts,
        List<String> reportDetails,
        Long handledBy,
        Instant handledAt,
        Instant latestReportedAt
) {

    public static AdminCommentReportResponse from(AdminCommentReportView view) {
        return new AdminCommentReportResponse(
                view.commentId(),
                view.postId(),
                view.postPreview(),
                view.creatorId(),
                view.creatorName(),
                view.authorMemberId(),
                view.authorName(),
                view.content(),
                view.blocked(),
                view.filterReasons(),
                view.filterStatus(),
                view.hiddenByAdmin(),
                view.status(),
                view.reportCount(),
                view.pendingReportCount(),
                view.reasonCounts(),
                view.reportDetails(),
                view.handledBy(),
                view.handledAt(),
                view.latestReportedAt());
    }
}
