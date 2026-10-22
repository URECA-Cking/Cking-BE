package kr.co.cking.post.presentation.dto;

import kr.co.cking.post.application.dto.CommentReportView;
import kr.co.cking.post.domain.CommentReportReason;

import java.time.Instant;

/** 접수된 댓글 신고. 이미 신고한 댓글을 다시 신고하면 처음 접수된 신고가 그대로 내려온다. */
public record CommentReportResponse(Long reportId, Long commentId, CommentReportReason reason, Instant createdAt) {

    public static CommentReportResponse from(CommentReportView view) {
        return new CommentReportResponse(view.reportId(), view.commentId(), view.reason(), view.createdAt());
    }
}
