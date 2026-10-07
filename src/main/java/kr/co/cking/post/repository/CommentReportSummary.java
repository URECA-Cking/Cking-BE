package kr.co.cking.post.repository;

import java.time.Instant;

/** 신고된 댓글 한 건의 신고 수와 가장 최근 신고 시각. 관리자 목록의 한 행이다. */
public record CommentReportSummary(Long commentId, Long reportCount, Instant latestReportedAt) {
}
