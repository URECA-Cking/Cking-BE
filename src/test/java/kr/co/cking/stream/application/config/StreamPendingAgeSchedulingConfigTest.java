package kr.co.cking.stream.application.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import kr.co.cking.common.config.SchedulingConfig;
import kr.co.cking.stream.scheduler.StreamPendingAgeRecorder;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

class StreamPendingAgeSchedulingConfigTest {

    private ApplicationContextRunner context(Execution execution) {
        return new ApplicationContextRunner()
                .withUserConfiguration(SchedulingConfig.class, StreamPendingAgeSchedulingConfig.class,
                        StreamPendingAgeRecorder.class, Dependencies.class)
                .withBean(Execution.class, () -> execution)
                .withPropertyValues("cking.stream.pending-age.interval-ms=10");
    }

    @Test
    void 공통_스케줄러가_막혀도_전용_스레드에서_기록한다() {
        Execution execution = new Execution();
        context(execution).run(context -> {
            try {
                assertThat(context).hasNotFailed();
                assertThat(execution.commonBlocked.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(execution.recordedWhileBlocked.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(execution.recordThread.get()).startsWith("stream-pending-age-");
                assertThat(context.getBean("streamPendingAgeTaskScheduler", ThreadPoolTaskScheduler.class)
                        .getScheduledThreadPoolExecutor().getCorePoolSize()).isEqualTo(1);
            } finally {
                execution.release.countDown();
            }
        });
    }

    @Test
    void 전체_스케줄링을_끄면_전용_풀과_예약_처리기를_만들지_않는다() {
        context(new Execution()).withPropertyValues("cking.scheduling.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean("streamPendingAgeTaskScheduler");
                    assertThat(context).doesNotHaveBean(ScheduledAnnotationBeanPostProcessor.class);
                });
    }

    static class Execution {
        final CountDownLatch commonBlocked = new CountDownLatch(1);
        final CountDownLatch recordedWhileBlocked = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicReference<String> recordThread = new AtomicReference<>();
    }

    @Configuration(proxyBeanMethods = false)
    static class Dependencies {
        @Bean
        StringRedisTemplate redisTemplate(Execution execution) {
            StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class, RETURNS_DEEP_STUBS);
            when(redisTemplate.opsForStream().pending(anyString(), anyString())).thenAnswer(invocation -> {
                if (execution.commonBlocked.getCount() == 0 && execution.release.getCount() == 1) {
                    execution.recordThread.set(Thread.currentThread().getName());
                    execution.recordedWhileBlocked.countDown();
                }
                return null;
            });
            return redisTemplate;
        }

        @Bean
        Clock clock() {
            return Clock.systemUTC();
        }

        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }

        @Bean
        ThreadPoolTaskScheduler taskScheduler() {
            ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
            scheduler.setPoolSize(1);
            scheduler.setThreadNamePrefix("common-test-");
            return scheduler;
        }

        @Bean
        BlockingCommonTask blockingCommonTask(Execution execution) {
            return new BlockingCommonTask(execution);
        }
    }

    static class BlockingCommonTask {
        private final Execution execution;

        BlockingCommonTask(Execution execution) {
            this.execution = execution;
        }

        @Scheduled(fixedDelay = 10)
        public void tick() throws InterruptedException {
            execution.commonBlocked.countDown();
            execution.release.await(10, TimeUnit.SECONDS);
        }
    }
}
