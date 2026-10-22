package kr.co.cking.post.repository;

import kr.co.cking.post.domain.CommentReportReason;

/** 한 댓글에 쌓인 신고를 사유별로 센 값. */
public record CommentReportReasonCount(Long commentId, CommentReportReason reason, Long count) {
}
