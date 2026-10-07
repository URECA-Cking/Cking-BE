package kr.co.cking.post.repository;

/** 재필터링 대상 댓글과 지금까지 재제출한 횟수. 본문은 담지 않는다. */
public record CommentFilterRetryCandidate(Long commentId, int attempts) {
}
