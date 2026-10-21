package kr.co.cking.notification.config;

import kr.co.cking.common.config.SchedulingConfig;
import kr.co.cking.notification.application.TicketRewardNotificationService;
import kr.co.cking.notification.scheduler.TicketRewardNotificationScheduler;
import kr.co.cking.ticket.application.RewardNotificationLedgerStore;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

class TicketRewardNotificationSchedulingConfigTest {
    private ApplicationContextRunner context(Execution execution) {
        return new ApplicationContextRunner()
                .withUserConfiguration(SchedulingConfig.class, TicketRewardNotificationSchedulingConfig.class,
                        TicketRewardNotificationScheduler.class, Dependencies.class)
                .withBean(Execution.class, () -> execution)
                .withPropertyValues("cking.notification.ticket-reward.enabled=true",
                        "cking.notification.ticket-reward.interval-ms=10");
    }

    @Test
    void 예약된_알림은_전용_스레드에서_실행한다() {
        Execution execution = new Execution(false);
        context(execution).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(execution.started.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(execution.thread.get()).startsWith("ticket-reward-notification-");
            assertThat(context.getBean("ticketRewardNotificationTaskScheduler", ThreadPoolTaskScheduler.class)
                    .getScheduledThreadPoolExecutor().getCorePoolSize()).isEqualTo(1);
        });
    }

    @Test
    void 알림이_막혀도_공용_예약작업은_진행한다() {
        Execution execution = new Execution(true);
        context(execution).run(context -> {
            try {
                assertThat(context).hasNotFailed();
                assertThat(execution.started.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(execution.commonWhileBlocked.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(execution.release.getCount()).isEqualTo(1);
                assertThat(execution.commonThread.get()).startsWith("common-test-");
            } finally {
                execution.release.countDown();
            }
        });
    }

    @Test
    void 알림기능_OFF면_전용풀과_알림작업을_만들지_않는다() {
        context(new Execution(false)).withPropertyValues("cking.notification.ticket-reward.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean("ticketRewardNotificationTaskScheduler");
                    assertThat(context).doesNotHaveBean(TicketRewardNotificationScheduler.class);
                    verifyNoInteractions(context.getBean(RewardNotificationLedgerStore.class),
                            context.getBean(TicketRewardNotificationService.class));
                });
    }

    @Test
    void 전체스케줄링_OFF면_전용풀과_예약처리기를_만들지_않는다() {
        context(new Execution(false)).withPropertyValues("cking.scheduling.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean("ticketRewardNotificationTaskScheduler");
                    assertThat(context).doesNotHaveBean(ScheduledAnnotationBeanPostProcessor.class);
                    verifyNoInteractions(context.getBean(RewardNotificationLedgerStore.class),
                            context.getBean(TicketRewardNotificationService.class));
                });
    }

    static class Execution {
        final boolean block;
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final CountDownLatch commonWhileBlocked = new CountDownLatch(1);
        final AtomicReference<String> thread = new AtomicReference<>();
        final AtomicReference<String> commonThread = new AtomicReference<>();

        Execution(boolean block) { this.block = block; }
    }

    @Configuration(proxyBeanMethods = false)
    static class Dependencies {
        @Bean
        RewardNotificationLedgerStore ledgers(Execution execution) {
            RewardNotificationLedgerStore store = mock(RewardNotificationLedgerStore.class);
            when(store.findDue(anyBoolean(), anyInt())).thenAnswer(invocation -> {
                execution.thread.set(Thread.currentThread().getName());
                execution.started.countDown();
                if (execution.block && !execution.release.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Test did not release notification task");
                }
                return List.of();
            });
            return store;
        }

        @Bean
        TicketRewardNotificationService notifications() { return mock(TicketRewardNotificationService.class); }

        @Bean
        ThreadPoolTaskScheduler taskScheduler() {
            ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
            scheduler.setPoolSize(1);
            scheduler.setThreadNamePrefix("common-test-");
            return scheduler;
        }

        @Bean
        CommonTask commonTask(Execution execution) { return new CommonTask(execution); }
    }

    static class CommonTask {
        private final Execution execution;

        CommonTask(Execution execution) { this.execution = execution; }

        @Scheduled(fixedDelay = 10)
        public void tick() {
            if (execution.block && execution.started.getCount() == 0 && execution.release.getCount() == 1) {
                execution.commonThread.set(Thread.currentThread().getName());
                execution.commonWhileBlocked.countDown();
            }
        }
    }
}
