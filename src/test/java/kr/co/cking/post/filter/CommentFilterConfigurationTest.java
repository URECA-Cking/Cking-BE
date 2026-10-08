package kr.co.cking.post.filter;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import kr.co.cking.post.scheduler.CommentFilterRetryScheduler;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.time.Clock;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class CommentFilterConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(CommentFilterConfiguration.class)
            .withBean(CommentFilterResultService.class, () -> mock(CommentFilterResultService.class));

    @Test
    void enabled_속성이_없으면_기본값으로_필터_Bean을_하나도_만들지_않는다() {
        runner.run(this::assertNoFilterBeans);
    }

    @Test
    void enabled가_false면_필터_Bean을_만들지_않는다() {
        runner.withPropertyValues("cking.comment-filter.enabled=false").run(this::assertNoFilterBeans);
    }

    @Test
    void enabled가_true일_때만_Client_Worker_Dispatcher_전용_Executor를_만든다() {
        runner.withPropertyValues("cking.comment-filter.enabled=true").run(context -> {
            assertThat(context).hasNotFailed()
                    .hasSingleBean(CommentFilterClient.class)
                    .hasSingleBean(CommentFilterWorker.class)
                    .hasSingleBean(CommentFilterDispatcher.class);
            ThreadPoolTaskExecutor executor = context.getBean("commentFilterExecutor", ThreadPoolTaskExecutor.class);
            assertThat(executor.getCorePoolSize()).isEqualTo(2);
            assertThat(executor.getMaxPoolSize()).isEqualTo(2);
            assertThat(executor.getThreadPoolExecutor().getQueue().remainingCapacity()).isEqualTo(100);
            assertThat(executor.getThreadNamePrefix()).isEqualTo("comment-filter-");
        });
    }

    @Test
    void Executor_설정을_바꾸면_반영한다() {
        runner.withPropertyValues(
                "cking.comment-filter.enabled=true",
                "cking.comment-filter.executor.core-pool-size=1",
                "cking.comment-filter.executor.max-pool-size=3",
                "cking.comment-filter.executor.queue-capacity=7").run(context -> {
            ThreadPoolTaskExecutor executor = context.getBean("commentFilterExecutor", ThreadPoolTaskExecutor.class);
            assertThat(executor.getCorePoolSize()).isEqualTo(1);
            assertThat(executor.getMaxPoolSize()).isEqualTo(3);
            assertThat(executor.getThreadPoolExecutor().getQueue().remainingCapacity()).isEqualTo(7);
        });
    }

    @Test
    void 잘못된_Executor_설정은_기동_시_거부한다() {
        assertStartupFails("cking.comment-filter.executor.core-pool-size=0", "pool size");
        assertStartupFails("cking.comment-filter.executor.core-pool-size=3", "pool size", "cking.comment-filter.executor.max-pool-size=2");
        assertStartupFails("cking.comment-filter.executor.queue-capacity=0", "queueCapacity");
        assertStartupFails("cking.comment-filter.executor.await-termination-seconds=0", "종료 대기 시간");
    }

    @Test
    void 필터가_꺼져_있으면_재필터링_스케줄러도_만들지_않는다() {
        schedulerRunner().run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(CommentFilterRetryScheduler.class));
        schedulerRunner().withPropertyValues("cking.comment-filter.enabled=false",
                        "cking.comment-filter.retry.enabled=true")
                .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(CommentFilterRetryScheduler.class));
    }

    @Test
    void 재필터링을_끄면_필터가_켜져_있어도_스케줄러를_만들지_않는다() {
        schedulerRunner().withPropertyValues("cking.comment-filter.enabled=true",
                        "cking.comment-filter.retry.enabled=false")
                .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(CommentFilterRetryScheduler.class));
    }

    @Test
    void 필터와_재필터링이_모두_켜져_있을_때만_스케줄러를_만든다() {
        schedulerRunner().withPropertyValues("cking.comment-filter.enabled=true",
                        "cking.comment-filter.retry.enabled=true")
                .run(context -> assertThat(context).hasNotFailed().hasSingleBean(CommentFilterRetryScheduler.class));
    }

    private ApplicationContextRunner schedulerRunner() {
        return new ApplicationContextRunner()
                .withUserConfiguration(CommentFilterRetryScheduler.class)
                .withBean(CommentFilterRetryService.class, () -> mock(CommentFilterRetryService.class))
                .withBean(CommentFilterDispatcher.class, () -> mock(CommentFilterDispatcher.class))
                .withBean(CommentFilterProperties.class, CommentFilterProperties::new)
                .withBean(Clock.class, Clock::systemUTC)
                .withBean(MeterRegistry.class, SimpleMeterRegistry::new);
    }

    private void assertNoFilterBeans(AssertableApplicationContext context) {
        assertThat(context).hasNotFailed()
                .doesNotHaveBean(CommentFilterClient.class)
                .doesNotHaveBean(CommentFilterWorker.class)
                .doesNotHaveBean(CommentFilterDispatcher.class)
                .doesNotHaveBean(CommentFilterProperties.class)
                .doesNotHaveBean("commentFilterExecutor");
    }

    private void assertStartupFails(String invalidSetting, String expectedMessage, String... extraSettings) {
        Consumer<AssertableApplicationContext> assertion = context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).rootCause()
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(expectedMessage);
        };
        String[] properties = new String[2 + extraSettings.length];
        properties[0] = "cking.comment-filter.enabled=true";
        properties[1] = invalidSetting;
        System.arraycopy(extraSettings, 0, properties, 2, extraSettings.length);
        runner.withPropertyValues(properties).run(assertion::accept);
    }
}
