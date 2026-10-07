package kr.co.cking.post.filter;

import kr.co.cking.post.domain.CommentFilterStatus;
import kr.co.cking.post.repository.CommentFilterRetryCandidate;
import kr.co.cking.post.repository.CreatorPostCommentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/** 재필터링 스케줄러의 조회와 재제출 확보를 짧은 Transaction으로 제공한다. 필터 서비스 호출은 하지 않는다. */
@Service
@RequiredArgsConstructor
public class CommentFilterRetryService {

    private static final List<CommentFilterStatus> RETRYABLE = List.of(
            CommentFilterStatus.PENDING, CommentFilterStatus.FAILED);

    private final CreatorPostCommentRepository commentRepository;

    @Transactional(readOnly = true)
    public List<CommentFilterRetryCandidate> findCandidates(
            Instant now, Instant pendingBefore, int maxAttempts, int limit) {
        return commentRepository.findRetryCandidates(
                RETRYABLE, maxAttempts, pendingBefore, now, PageRequest.of(0, limit));
    }

    /**
     * 시도 횟수를 하나 올리고 다음 시도 시각을 정해 재제출 권한을 확보한다. 조회한 뒤 판정이 끝났거나 본문이 수정되었으면
     * false이며, 이때는 제출하지 않는다.
     */
    @Transactional
    public boolean claim(CommentFilterRetryCandidate candidate, Instant nextAttemptAt) {
        return commentRepository.claimForRetry(
                candidate.commentId(), RETRYABLE, candidate.attempts(), candidate.updatedAt(), nextAttemptAt) == 1;
    }

    /**
     * 확보했지만 제출하지 못한 재시도를 되돌려, 필터가 실행되지 않은 시도가 횟수를 소진하지 않게 한다.
     * 되돌린 뒤에는 다음 주기에 곧바로 다시 대상이 된다(확보 전 다음 시도 시각으로 복원).
     */
    @Transactional
    public boolean release(CommentFilterRetryCandidate candidate) {
        return commentRepository.releaseRetryClaim(
                candidate.commentId(), RETRYABLE, candidate.attempts() + 1, candidate.nextAttemptAt()) == 1;
    }
}
