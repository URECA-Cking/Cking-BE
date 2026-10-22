package kr.co.cking.post.filter;

/** 판정이 필요한 댓글. 작성·수정 Transaction이 Commit된 뒤에만 필터 서비스에 요청한다. */
public record CommentFilterRequestedEvent(Long commentId) {
}
