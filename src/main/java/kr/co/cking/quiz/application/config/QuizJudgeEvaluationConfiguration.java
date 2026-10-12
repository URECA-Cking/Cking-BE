package kr.co.cking.quiz.application.config;

import kr.co.cking.quiz.judge.openai.OpenAiJudgeProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(QuizJudgeEvaluationProperties.class)
public class QuizJudgeEvaluationConfiguration {

    /**
     * RUNNING 기준 시간이 평가 한 번의 전체 실행 상한보다 짧으면 정상 실행 중인 Job이 UNKNOWN으로 정리되므로 기동을
     * 막는다.
     */
    QuizJudgeEvaluationConfiguration(QuizJudgeEvaluationProperties evaluation, OpenAiJudgeProperties provider) {
        if (evaluation.getStaleRunning().compareTo(provider.getExecutionTimeout()) <= 0) {
            throw new IllegalStateException(
                    "cking.quiz.judge.stale-running은 cking.quiz.judge.execution-timeout보다 길어야 합니다.");
        }
    }

    /**
     * Quiz 생성 Executor와 자원을 나누는 Judge 전용 Executor. bounded queue와 AbortPolicy로 포화 시 제출을 거절한다.
     * 요청 thread에서 Provider를 호출하지 않도록 CallerRunsPolicy를 쓰지 않는다. thread는 작업이 들어올 때 만든다.
     */
    @Bean(name = "quizJudgeExecutor")
    ThreadPoolTaskExecutor quizJudgeExecutor(QuizJudgeEvaluationProperties properties) {
        QuizJudgeEvaluationProperties.Executor settings = properties.getExecutor();
        if (settings.getPoolSize() <= 0 || settings.getQueueCapacity() <= 0
                || settings.getAwaitTerminationSeconds() <= 0) {
            throw new IllegalArgumentException("Quiz Judge Executor 설정은 양수여야 합니다.");
        }
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(settings.getPoolSize());
        executor.setMaxPoolSize(settings.getPoolSize());
        executor.setQueueCapacity(settings.getQueueCapacity());
        executor.setThreadNamePrefix("quiz-judge-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(settings.getAwaitTerminationSeconds());
        return executor;
    }
}
