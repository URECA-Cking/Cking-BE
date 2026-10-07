package kr.co.cking.post.filter;

import java.util.concurrent.Executor;

/** Commit된 댓글을 전용 Executor에 제출하는 경계. 큐가 가득 차면 제출이 거절되어 예외가 난다. */
public class CommentFilterDispatcher {

    private final Executor executor;
    private final CommentFilterWorker worker;

    public CommentFilterDispatcher(Executor executor, CommentFilterWorker worker) {
        this.executor = executor;
        this.worker = worker;
    }

    public void dispatch(Long commentId) {
        executor.execute(() -> worker.process(commentId));
    }
}
