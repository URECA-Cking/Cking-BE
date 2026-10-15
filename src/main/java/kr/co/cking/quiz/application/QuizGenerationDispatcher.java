package kr.co.cking.quiz.application;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import java.util.concurrent.RejectedExecutionException;

/** 커밋된 Quiz 생성 작업을 전용 Executor에 제출한다. 큐가 가득 차면 거절하고 false를 돌려준다. */
@Component
public class QuizGenerationDispatcher {

    private final ThreadPoolTaskExecutor executor;
    private final QuizGenerationWorker worker;

    public QuizGenerationDispatcher(@Qualifier("quizGenerationExecutor") ThreadPoolTaskExecutor executor,
                                    QuizGenerationWorker worker) {
        this.executor = executor;
        this.worker = worker;
    }

    public boolean dispatch(Long generationId) {
        return submit(() -> worker.run(generationId));
    }

    /** Creator가 원문을 확정한 작업의 Quiz 생성 단계를 제출한다(이슈 #604). */
    public boolean dispatchGeneration(Long generationId) {
        return submit(() -> worker.runGeneration(generationId));
    }

    private boolean submit(Runnable task) {
        try {
            executor.execute(task);
            return true;
        } catch (RejectedExecutionException exception) {
            return false;
        }
    }
}
