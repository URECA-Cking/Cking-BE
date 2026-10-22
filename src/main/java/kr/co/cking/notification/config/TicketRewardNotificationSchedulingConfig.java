package kr.co.cking.notification.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * 응모권 지급 완료 알림 생성이 짧은 주기로 돌아도 시간 민감한 공통 Scheduler를 지연시키지 않도록 전용 실행 풀을 둔다(이슈 #732).
 * 알림 기능이 꺼진 환경에서는 스케줄러가 없으므로 실행 풀도 만들지 않는다.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "cking.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class TicketRewardNotificationSchedulingConfig {

    @Bean(name = "ticketRewardNotificationTaskScheduler")
    @ConditionalOnProperty(name = "cking.notification.ticket-reward.enabled", havingValue = "true")
    ThreadPoolTaskScheduler ticketRewardNotificationTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("ticket-reward-notification-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        return scheduler;
    }
}
