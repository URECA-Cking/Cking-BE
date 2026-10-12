package kr.co.cking.post.presentation.dto;

import kr.co.cking.post.application.dto.AdminCommentReportActionView;
import kr.co.cking.post.domain.CommentReportStatus;

/**
 * 관리자 신고 조치 결과.
 *
 * @param status             처리 결과(숨김 RESOLVED, 반려 REJECTED)
 * @param handledReportCount 이번 요청으로 처리한 접수 신고 수. 같은 조치를 다시 요청하면 0이다
 */
public record AdminCommentReportActionResponse(Long commentId, CommentReportStatus status, int handledReportCount) {

    public static AdminCommentReportActionResponse from(AdminCommentReportActionView view) {
        return new AdminCommentReportActionResponse(view.commentId(), view.status(), view.handledReportCount());
    }
}
