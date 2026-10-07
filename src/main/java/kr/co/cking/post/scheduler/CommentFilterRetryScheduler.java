package kr.co.cking.post.scheduler;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import kr.co.cking.post.filter.CommentFilterDispatcher;
import kr.co.cking.post.filter.CommentFilterProperties;
import kr.co.cking.post.filter.CommentFilterRetryService;
import kr.co.cking.post.repository.CommentFilterRetryCandidate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;

/**
 * 판정하지 못한 댓글(PENDING·FAILED)을 다시 필터에 제출한다(이슈 #477).
 *
 * <p>필터 장애, Executor 큐 포화로 인한 제출 거절, 서버 재시작으로 놓친 댓글이 대상이다. 제출하기 전에 시도 횟수를
 * 올리고 다음 시도 시각을 조건부 UPDATE로 확보하므로, 큐에서 처리 중인 댓글을 다음 주기에 또 고르지 않고 필터가
 * 응답하지 않아도 횟수가 쌓여 상한에서 멈춘다. 한 번에 제출하는 건수는 Executor 큐의 남은 자리를 넘지 않아 새 댓글의
 * 첫 판정 제출을 밀어내지 않는다. 상한에 닿은 댓글은 상태를 바꾸지 않고(BLOCK 판정과 노출 상태 유지) 마지막 시도를
 * 제출할 때 {@code error} 로그와 카운터로 남긴다.
 *
 * <p>필터가 꺼져 있으면(Dispatcher가 없으면) 만들어지지 않는다.
 */
@Component
@ConditionalOnProperty(
        name = {"cking.comment-filter.enabled", "cking.comment-filter.retry.enabled"},
        havingValue = "true")
@Slf4j
public class CommentFilterRetryScheduler {

    private final CommentFilterRetryService retryService;
    private final CommentFilterDispatcher dispatcher;
    private final CommentFilterProperties.Retry properties;
    private final Clock clock;
    private final Counter attemptedCounter;
    private final Counter exhaustedCounter;
    private final Counter queueFullCounter;
    private final Counter submitFailedCounter;

    public CommentFilterRetryScheduler(
            CommentFilterRetryService retryService,
            CommentFilterDispatcher dispatcher,
            CommentFilterProperties properties,
            Clock clock,
            MeterRegistry meterRegistry) {
        this.retryService = retryService;
        this.dispatcher = dispatcher;
        this.properties = properties.getRetry();
        this.clock = clock;
        this.attemptedCounter = counter(meterRegistry, "comment_filter.retry.attempted");
        this.exhaustedCounter = counter(meterRegistry, "comment_filter.retry.exhausted");
        this.queueFullCounter = counter(meterRegistry, "comment_filter.retry.queue_full");
        this.submitFailedCounter = counter(meterRegistry, "comment_filter.retry.submit_failed");
    }

    @Scheduled(
            fixedDelayString = "${cking.comment-filter.retry.interval-ms:60000}",
            initialDelayString = "${cking.comment-filter.retry.interval-ms:60000}")
    public void retry() {
        int limit = Math.min(properties.getBatchSize(), dispatcher.remainingQueueCapacity());
        if (limit <= 0) {
            queueFullCounter.increment();
            log.debug("댓글 필터 Executor 큐에 자리가 없어 이번 재필터링은 건너뜁니다.");
            return;
        }

        Instant now = clock.instant();
        List<CommentFilterRetryCandidate> candidates = retryService.findCandidates(
                now, now.minus(properties.getPendingGracePeriod()), properties.getMaxAttempts(), limit);
        for (CommentFilterRetryCandidate candidate : candidates) {
            if (!resubmit(candidate, now)) {
                break;
            }
        }
    }

    /** @return 다음 후보도 이어서 처리하려면 true. 큐가 가득 차 거절되면 나머지도 거절되므로 false */
    private boolean resubmit(CommentFilterRetryCandidate candidate, Instant now) {
        Long commentId = candidate.commentId();
        Instant nextAttemptAt = now.plus(properties.backoff(candidate.attempts()));
        try {
            if (!retryService.claim(candidate, nextAttemptAt)) {
                return true;
            }
        } catch (RuntimeException exception) {
            submitFailedCounter.increment();
            log.warn("댓글 필터 재제출을 확보하지 못했습니다. 다음 주기에 다시 처리합니다. commentId={}", commentId, exception);
            return true;
        }

        try {
            dispatcher.dispatch(commentId);
        } catch (RejectedExecutionException exception) {
            // 큐의 남은 자리를 확인한 뒤 새 댓글 작업이 자리를 차지한 경우다. 필터가 실행되지 않았으므로 횟수를 돌려준다.
            queueFullCounter.increment();
            log.debug("댓글 필터 Executor 큐가 가득 차 재제출을 되돌렸습니다. commentId={}", commentId);
            release(candidate);
            return false;
        } catch (RuntimeException exception) {
            submitFailedCounter.increment();
            log.warn("댓글 필터 재제출에 실패했습니다. 시도 횟수를 되돌립니다. commentId={}", commentId, exception);
            release(candidate);
            return true;
        }

        attemptedCounter.increment();
        if (candidate.attempts() + 1 >= properties.getMaxAttempts()) {
            exhaustedCounter.increment();
            log.error("댓글 필터 재시도 상한에 닿았습니다. 마지막 재시도를 제출했습니다. commentId={}, attempts={}",
                    commentId, candidate.attempts() + 1);
        }
        return true;
    }

    /** 되돌리기에 실패하면 이번 시도는 소진된 채 남는다. 필터 호출 자체가 막힌 것은 아니므로 다음 시도 시각에 다시 처리된다. */
    private void release(CommentFilterRetryCandidate candidate) {
        try {
            retryService.release(candidate);
        } catch (RuntimeException exception) {
            log.warn("댓글 필터 재제출 확보를 되돌리지 못했습니다. commentId={}", candidate.commentId(), exception);
        }
    }

    private static Counter counter(MeterRegistry meterRegistry, String name) {
        return Counter.builder(name).register(meterRegistry);
    }
}
