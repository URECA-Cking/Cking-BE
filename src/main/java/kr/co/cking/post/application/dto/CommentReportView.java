package kr.co.cking.post.application.dto;

import kr.co.cking.post.domain.CommentReportReason;
import kr.co.cking.post.domain.CreatorPostCommentReport;

import java.time.Instant;

/** 접수된 댓글 신고. 신고자 정보는 담지 않는다. */
public record CommentReportView(Long reportId, Long commentId, CommentReportReason reason, Instant createdAt) {

    public static CommentReportView from(CreatorPostCommentReport report) {
        return new CommentReportView(
                report.getReportId(), report.getCommentId(), report.getReason(), report.getCreatedAt());
    }
}
