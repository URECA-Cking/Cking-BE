package kr.co.cking.quiz.application.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(QuizGenerationProperties.class)
public class QuizGenerationConfiguration {

    /**
     * 다른 비동기 작업과 자원을 나누는 Quiz 생성 전용 Executor. bounded queue와 AbortPolicy로 포화 시 제출을 거절한다.
     * 요청 thread에서 Provider를 호출하지 않도록 CallerRunsPolicy를 쓰지 않는다.
     */
    @Bean(name = "quizGenerationExecutor")
    ThreadPoolTaskExecutor quizGenerationExecutor(QuizGenerationProperties properties) {
        QuizGenerationProperties.Executor settings = properties.getExecutor();
        if (settings.getPoolSize() <= 0 || settings.getQueueCapacity() <= 0
                || settings.getAwaitTerminationSeconds() <= 0) {
            throw new IllegalArgumentException("Quiz 생성 Executor 설정은 양수여야 합니다.");
        }
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(settings.getPoolSize());
        executor.setMaxPoolSize(settings.getPoolSize());
        executor.setQueueCapacity(settings.getQueueCapacity());
        executor.setThreadNamePrefix("quiz-generation-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(settings.getAwaitTerminationSeconds());
        return executor;
    }
}
