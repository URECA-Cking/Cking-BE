package kr.co.cking.subscriptionverification.infrastructure.recovery;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/** 구독 인증 복구가 시간 민감한 공용 Scheduler를 지연시키지 않도록 전용 실행 풀을 둔다. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "cking.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class SubscriptionVerificationRecoverySchedulingConfig {

    @Bean(name = "subscriptionVerificationRecoveryTaskScheduler")
    ThreadPoolTaskScheduler subscriptionVerificationRecoveryTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("subscription-verification-recovery-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        return scheduler;
    }
}
