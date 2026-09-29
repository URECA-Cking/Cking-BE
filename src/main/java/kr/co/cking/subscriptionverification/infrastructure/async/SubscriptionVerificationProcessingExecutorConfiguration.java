package kr.co.cking.subscriptionverification.infrastructure.async;

import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/** Spring 기본 Async executor와 분리된 구독 인증 전용 실행 자원을 구성한다. */
@Configuration(proxyBeanMethods = false)
@EnableAsync
@EnableConfigurationProperties(SubscriptionVerificationProcessingExecutorProperties.class)
public class SubscriptionVerificationProcessingExecutorConfiguration {

    /** bounded queue와 AbortPolicy를 사용하는 구독 인증 전용 Executor를 만든다. */
    @Bean(name = "subscriptionVerificationExecutor")
    ThreadPoolTaskExecutor subscriptionVerificationExecutor(
            SubscriptionVerificationProcessingExecutorProperties properties) {
        validate(properties);

        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(properties.getCorePoolSize());
        executor.setMaxPoolSize(properties.getMaxPoolSize());
        executor.setQueueCapacity(properties.getQueueCapacity());
        executor.setThreadNamePrefix("subscription-verification-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(properties.getAwaitTerminationSeconds());
        return executor;
    }

    /** 설정값이 bounded 실행·Provider 호출 제한 계약을 만족하는지 검증한다. */
    private void validate(SubscriptionVerificationProcessingExecutorProperties properties) {
        if (properties.getCorePoolSize() <= 0
                || properties.getMaxPoolSize() < properties.getCorePoolSize()) {
            throw new IllegalArgumentException("Executor pool size 설정이 올바르지 않습니다.");
        }
        if (properties.getQueueCapacity() <= 0) {
            throw new IllegalArgumentException("Executor queueCapacity는 양수여야 합니다.");
        }
        if (properties.getProviderMaxConcurrentCalls() != properties.getMaxPoolSize()) {
            throw new IllegalArgumentException("Provider 동시 호출 수와 Executor maxPoolSize는 같아야 합니다.");
        }
        if (properties.getProcessingLeaseDuration() == null
                || properties.getProcessingLeaseDuration().isNegative()
                || properties.getProcessingLeaseDuration().isZero()) {
            throw new IllegalArgumentException("Processing lease duration은 양수여야 합니다.");
        }
        if (properties.getAwaitTerminationSeconds() <= 0) {
            throw new IllegalArgumentException("Executor 종료 대기 시간은 양수여야 합니다.");
        }
    }
}
