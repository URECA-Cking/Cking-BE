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
        try {
            executor.execute(() -> worker.run(generationId));
            return true;
        } catch (RejectedExecutionException exception) {
            return false;
        }
    }
}
