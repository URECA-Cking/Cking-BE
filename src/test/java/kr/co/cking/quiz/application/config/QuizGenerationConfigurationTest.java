package kr.co.cking.quiz.application.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.time.Duration;
import java.util.concurrent.ThreadPoolExecutor;

import static org.assertj.core.api.Assertions.assertThat;

class QuizGenerationConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(QuizGenerationConfiguration.class);

    @Test
    void 기본값은_기능_꺼짐과_pool_2_queue_10의_AbortPolicy_Executor다() {
        runner.run(context -> {
            QuizGenerationProperties properties = context.getBean(QuizGenerationProperties.class);
            assertThat(properties.isEnabled()).isFalse();
            assertThat(properties.getStalePending()).isEqualTo(Duration.ofMinutes(10));
            assertThat(properties.getStaleRunning()).isEqualTo(Duration.ofMinutes(15));

            ThreadPoolTaskExecutor executor = context.getBean("quizGenerationExecutor", ThreadPoolTaskExecutor.class);
            assertThat(executor.getCorePoolSize()).isEqualTo(2);
            assertThat(executor.getMaxPoolSize()).isEqualTo(2);
            assertThat(executor.getQueueCapacity()).isEqualTo(10);
            assertThat(executor.getThreadPoolExecutor().getRejectedExecutionHandler())
                    .isInstanceOf(ThreadPoolExecutor.AbortPolicy.class);
        });
    }

    @Test
    void 오래된_작업_기준_시간이_0이나_음수면_기동하지_않는다() {
        for (String property : new String[] {
                "cking.quiz.generation.stale-pending=PT0S",
                "cking.quiz.generation.stale-pending=-PT1M",
                "cking.quiz.generation.stale-running=PT0S",
                "cking.quiz.generation.stale-running=-PT1M"}) {
            runner.withPropertyValues(property).run(context -> {
                assertThat(context).as(property).hasFailed();
                assertThat(context.getStartupFailure()).as(property)
                        .hasStackTraceContaining("staleDurationsPositive");
            });
        }
    }

    @Test
    void 기준_시간이_없으면_유효하지_않다() {
        QuizGenerationProperties properties = new QuizGenerationProperties();
        assertThat(properties.isStaleDurationsPositive()).isTrue();

        properties.setStalePending(null);
        assertThat(properties.isStaleDurationsPositive()).isFalse();
        properties.setStalePending(Duration.ofMinutes(10));
        properties.setStaleRunning(null);
        assertThat(properties.isStaleDurationsPositive()).isFalse();
    }

    @Test
    void 양수_기준_시간은_그대로_쓴다() {
        runner.withPropertyValues(
                        "cking.quiz.generation.stale-pending=PT1S",
                        "cking.quiz.generation.stale-running=PT30M")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    QuizGenerationProperties properties = context.getBean(QuizGenerationProperties.class);
                    assertThat(properties.getStalePending()).isEqualTo(Duration.ofSeconds(1));
                    assertThat(properties.getStaleRunning()).isEqualTo(Duration.ofMinutes(30));
                });
    }

    @Test
    void 잘못된_Executor_설정이면_기동하지_않는다() {
        runner.withPropertyValues("cking.quiz.generation.executor.queue-capacity=0")
                .run(context -> assertThat(context).hasFailed());
    }
}
