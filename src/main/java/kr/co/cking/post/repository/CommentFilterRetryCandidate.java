package kr.co.cking.post.repository;

import java.time.Instant;

/**
 * 재필터링 대상 댓글과 조회 당시의 상태. 본문은 담지 않는다.
 *
 * @param attempts      지금까지 재제출한 횟수
 * @param updatedAt     조회 당시의 마지막 수정 시각. 선점할 때 같은 본문인지 확인하는 데 쓴다.
 * @param nextAttemptAt 조회 당시의 다음 시도 시각. 제출에 실패해 선점을 되돌릴 때 복원한다. 재시도한 적 없으면 null
 */
public record CommentFilterRetryCandidate(
        Long commentId, int attempts, Instant updatedAt, Instant nextAttemptAt) {
}
