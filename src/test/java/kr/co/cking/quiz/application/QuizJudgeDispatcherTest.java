package kr.co.cking.quiz.application;

import kr.co.cking.quiz.domain.QuizJudgeFailure;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.RejectedExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class QuizJudgeDispatcherTest {

    private static final Instant NOW = Instant.parse("2026-10-12T04:00:00Z");

    private final ThreadPoolTaskExecutor executor = mock(ThreadPoolTaskExecutor.class);
    private final QuizJudgeWorker worker = mock(QuizJudgeWorker.class);
    private final QuizJudgeEvaluationStore store = mock(QuizJudgeEvaluationStore.class);
    private final QuizJudgeDispatcher dispatcher = new QuizJudgeDispatcher(executor, worker, store,
            Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void 제출한_작업은_Worker를_실행하고_끝나면_다시_제출할_수_있다() {
        dispatcher.dispatch(1L);

        Runnable task = submitted(1);
        verify(worker, never()).run(anyLong());
        task.run();
        verify(worker).run(1L);

        dispatcher.dispatch(1L);
        submitted(2);
    }

    @Test
    void 대기_중이거나_실행_중인_Job은_다시_제출하지_않는다() {
        dispatcher.dispatch(1L);
        dispatcher.dispatch(1L);
        dispatcher.dispatch(2L);

        verify(executor, times(2)).execute(any(Runnable.class));
    }

    @Test
    void Worker가_실패해도_제출_표시를_지운다() {
        willThrow(new IllegalStateException("boom")).given(worker).run(1L);
        dispatcher.dispatch(1L);
        Runnable task = submitted(1);
        try {
            task.run();
        } catch (IllegalStateException ignored) {
            // Executor thread에서 난 예외다.
        }

        dispatcher.dispatch(1L);
        submitted(2);
    }

    @Test
    void Executor가_거절하면_PENDING을_DISPATCH_REJECTED로_끝내고_다음_제출은_받는다() {
        willThrow(new RejectedExecutionException("full")).given(executor).execute(any(Runnable.class));

        dispatcher.dispatch(1L);

        verify(store).failPending(1L, QuizJudgeFailure.DISPATCH_REJECTED, NOW);
        verify(worker, never()).run(anyLong());
        assertThat(dispatcher.submit(1L)).isFalse();
        verify(store, times(2)).failPending(1L, QuizJudgeFailure.DISPATCH_REJECTED, NOW);
    }

    @Test
    void Transaction_안에서_부르면_커밋_뒤에_제출하고_롤백되면_제출하지_않는다() {
        TransactionSynchronizationManager.initSynchronization();
        try {
            dispatcher.dispatch(1L);
            verify(executor, never()).execute(any(Runnable.class));
            for (TransactionSynchronization synchronization : TransactionSynchronizationManager.getSynchronizations()) {
                synchronization.afterCommit();
            }
            submitted(1);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }

        TransactionSynchronizationManager.initSynchronization();
        try {
            dispatcher.dispatch(2L);
            for (TransactionSynchronization synchronization : TransactionSynchronizationManager.getSynchronizations()) {
                synchronization.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
            }
            submitted(1);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    private Runnable submitted(int times) {
        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
        verify(executor, times(times)).execute(task.capture());
        return task.getValue();
    }
}
