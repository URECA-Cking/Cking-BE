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
     * 시도 횟수를 하나 올리고 다음 시도 시각을 정해 재제출 권한을 확보한다. 그사이 판정이 끝났거나 본문이 수정되어
     * 횟수가 바뀌었으면 false이며, 이때는 제출하지 않는다.
     */
    @Transactional
    public boolean claim(Long commentId, int expectedAttempts, Instant nextAttemptAt) {
        return commentRepository.claimForRetry(commentId, RETRYABLE, expectedAttempts, nextAttemptAt) == 1;
    }
}
