package kr.co.cking.post.repository;

import java.time.Instant;

/** 한 관리자가 그 댓글의 신고를 마지막으로 처리한 시각. */
public record CommentReportHandling(Long commentId, Long handledBy, Instant handledAt) {
}
