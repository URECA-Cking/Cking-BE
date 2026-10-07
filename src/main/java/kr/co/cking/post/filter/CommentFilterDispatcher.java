package kr.co.cking.post.filter;

import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/** Commit된 댓글을 전용 Executor에 제출하는 경계. 큐가 가득 차면 제출이 거절되어 예외가 난다. */
public class CommentFilterDispatcher {

    private final ThreadPoolTaskExecutor executor;
    private final CommentFilterWorker worker;

    public CommentFilterDispatcher(ThreadPoolTaskExecutor executor, CommentFilterWorker worker) {
        this.executor = executor;
        this.worker = worker;
    }

    /** 큐에 더 넣을 수 있는 자리. 재필터링이 새 댓글의 제출을 거절시키지 않도록 한 번에 제출할 건수를 제한하는 데 쓴다. */
    public int remainingQueueCapacity() {
        return executor.getThreadPoolExecutor().getQueue().remainingCapacity();
    }

    public void dispatch(Long commentId) {
        executor.execute(() -> worker.process(commentId));
    }
}
