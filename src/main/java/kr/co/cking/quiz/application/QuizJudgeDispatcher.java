package kr.co.cking.quiz.application;

import kr.co.cking.quiz.domain.QuizJudgeFailure;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;

/**
 * 새로 접수한 평가 Job을 Judge 전용 Executor에 제출한다(이슈 #529).
 *
 * <ul>
 *   <li>새 Job({@code Admission.created()}가 true)만 제출한다. 같은 requestId 재요청은 제출하지 않는다</li>
 *   <li>Transaction 안에서 부르면 커밋 뒤에 제출해, Worker가 커밋되지 않은 Job을 읽지 않게 한다</li>
 *   <li>이 인스턴스에서 대기·실행 중인 Job은 다시 제출하지 않는다. 다른 인스턴스·다른 경로의 중복 실행은 Worker의
 *       조건부 점유(PENDING → RUNNING)가 막는다</li>
 *   <li>Executor가 거절하면 PENDING일 때만 FAILED({@code DISPATCH_REJECTED})로 끝내 진행 중 자리를 비운다. 접수된
 *       시도이므로 시도 횟수는 되돌리지 않는다</li>
 * </ul>
 */
@Slf4j
@Component
public class QuizJudgeDispatcher {

    private final ThreadPoolTaskExecutor executor;
    private final QuizJudgeWorker worker;
    private final QuizJudgeEvaluationStore store;
    private final Clock clock;
    private final Set<Long> inFlight = ConcurrentHashMap.newKeySet();

    public QuizJudgeDispatcher(@Qualifier("quizJudgeExecutor") ThreadPoolTaskExecutor executor, QuizJudgeWorker worker,
                               QuizJudgeEvaluationStore store, Clock clock) {
        this.executor = executor;
        this.worker = worker;
        this.store = store;
        this.clock = clock;
    }

    public void dispatch(Long jobId) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    submit(jobId);
                }
            });
            return;
        }
        submit(jobId);
    }

    /** @return Executor가 받았으면 true */
    boolean submit(Long jobId) {
        if (!inFlight.add(jobId)) {
            log.warn("이미 제출한 평가 Job이라 다시 제출하지 않습니다. jobId={}", jobId);
            return false;
        }
        try {
            executor.execute(() -> {
                try {
                    worker.run(jobId);
                } finally {
                    inFlight.remove(jobId);
                }
            });
            return true;
        } catch (RejectedExecutionException exception) {
            inFlight.remove(jobId);
            log.warn("평가 Executor가 Job을 받지 못했습니다. jobId={}", jobId);
            store.failPending(jobId, QuizJudgeFailure.DISPATCH_REJECTED, clock.instant());
            return false;
        }
    }
}
