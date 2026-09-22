package kr.co.cking.snapshot.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/** Snapshot 복구 배치가 시간 민감한 공통 Scheduler를 지연시키지 않도록 전용 실행 풀을 둔다. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "cking.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class SnapshotRecoverySchedulingConfig {

    @Bean(name = "snapshotRecoveryTaskScheduler")
    ThreadPoolTaskScheduler snapshotRecoveryTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("snapshot-recovery-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        return scheduler;
    }
}
