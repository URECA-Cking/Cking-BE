package kr.co.cking.post.application.dto;

import kr.co.cking.post.domain.CommentReportStatus;

/**
 * 관리자가 신고된 댓글을 처리한 결과(이슈 #499).
 *
 * @param status             처리 결과. 숨김은 RESOLVED, 반려는 REJECTED
 * @param handledReportCount 이번 요청으로 처리한 접수(PENDING) 신고 수. 이미 같은 조치로 처리돼 다시 요청한 경우 0이다
 */
public record AdminCommentReportActionView(Long commentId, CommentReportStatus status, int handledReportCount) {
}
