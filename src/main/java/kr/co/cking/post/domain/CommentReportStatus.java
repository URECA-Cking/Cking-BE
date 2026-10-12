package kr.co.cking.post.domain;

/**
 * 댓글 신고 처리 상태(이슈 #499). 관리자는 같은 댓글의 접수(PENDING) 신고를 한 번에 숨김(RESOLVED)이나
 * 반려(REJECTED)로 처리한다. 의미는 V60 migration 주석을 따른다.
 */
public enum CommentReportStatus {
    PENDING,
    RESOLVED,
    REJECTED
}
