package kr.co.cking.stream.application.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * PEL 나이 기록은 PEL 회수와 같은 공통 Scheduler에서 돌면, 공통 Scheduler가 멈췄을 때 회수와 함께 멈춰 마지막 값에 머문다.
 * 회수가 멈춘 것을 드러내야 하므로 전용 실행 풀을 둔다.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "cking.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class StreamPendingAgeSchedulingConfig {

    @Bean(name = "streamPendingAgeTaskScheduler")
    ThreadPoolTaskScheduler streamPendingAgeTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("stream-pending-age-");
        return scheduler;
    }
}
