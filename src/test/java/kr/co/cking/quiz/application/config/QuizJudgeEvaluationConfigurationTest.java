package kr.co.cking.quiz.application.config;

import kr.co.cking.quiz.judge.openai.OpenAiJudgeConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.concurrent.ThreadPoolExecutor;

import static org.assertj.core.api.Assertions.assertThat;

class QuizJudgeEvaluationConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(OpenAiJudgeConfiguration.class, QuizJudgeEvaluationConfiguration.class)
            .withBean(ObjectMapper.class, ObjectMapper::new);

    @Test
    void 기본값은_기능_꺼짐과_PENDING_15분_RUNNING_5분이다() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            QuizJudgeEvaluationProperties properties = context.getBean(QuizJudgeEvaluationProperties.class);
            assertThat(properties.isEnabled()).isFalse();
            assertThat(properties.getStalePending()).isEqualTo(Duration.ofMinutes(15));
            assertThat(properties.getStaleRunning()).isEqualTo(Duration.ofMinutes(5));
        });
    }

    @Test
    void Judge_Executor는_생성_Executor와_분리된_pool_1_queue_4_AbortPolicy다() {
        runner.withUserConfiguration(QuizGenerationConfiguration.class).run(context -> {
            ThreadPoolTaskExecutor executor = context.getBean("quizJudgeExecutor", ThreadPoolTaskExecutor.class);
            assertThat(executor.getCorePoolSize()).isEqualTo(1);
            assertThat(executor.getMaxPoolSize()).isEqualTo(1);
            assertThat(executor.getQueueCapacity()).isEqualTo(4);
            assertThat(executor.getThreadNamePrefix()).isEqualTo("quiz-judge-");
            assertThat(executor.getThreadPoolExecutor().getRejectedExecutionHandler())
                    .isInstanceOf(ThreadPoolExecutor.AbortPolicy.class);
            assertThat(executor).isNotSameAs(context.getBean("quizGenerationExecutor"));
        });
    }

    @Test
    void 잘못된_Executor_설정이면_기동하지_않는다() {
        for (String property : new String[] {
                "cking.quiz.judge.executor.pool-size=0",
                "cking.quiz.judge.executor.queue-capacity=0",
                "cking.quiz.judge.executor.await-termination-seconds=0"}) {
            runner.withPropertyValues(property).run(context -> assertThat(context).as(property).hasFailed());
        }
    }

    @Test
    void 기준_시간이_0이나_음수면_기동하지_않는다() {
        for (String property : new String[] {
                "cking.quiz.judge.stale-pending=PT0S",
                "cking.quiz.judge.stale-pending=-PT1M",
                "cking.quiz.judge.stale-running=PT0S",
                "cking.quiz.judge.stale-running=-PT1M"}) {
            runner.withPropertyValues(property).run(context -> {
                assertThat(context).as(property).hasFailed();
                assertThat(context.getStartupFailure()).as(property).hasStackTraceContaining("staleDurationsPositive");
            });
        }
    }

    @Test
    void RUNNING_기준_시간은_전체_실행_상한보다_길어야_한다() {
        runner.withPropertyValues("cking.quiz.judge.stale-running=PT150S")
                .run(context -> assertThat(context.getStartupFailure())
                        .hasStackTraceContaining("stale-running은 cking.quiz.judge.execution-timeout보다"));
        runner.withPropertyValues("cking.quiz.judge.stale-running=PT151S")
                .run(context -> assertThat(context).hasNotFailed());
    }
}
