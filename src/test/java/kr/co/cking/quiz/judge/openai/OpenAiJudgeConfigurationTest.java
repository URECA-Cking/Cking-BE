package kr.co.cking.quiz.judge.openai;

import kr.co.cking.quiz.judge.QuizJudge;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class OpenAiJudgeConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(OpenAiJudgeConfiguration.class)
            .withBean(ObjectMapper.class, ObjectMapper::new);

    @Test
    void reason_설정은_200을_허용하고_201_300_1000은_기동에서_거부한다() {
        runner.withPropertyValues("cking.quiz.judge.reason-max-length=200").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(OpenAiJudgeProperties.class).getReasonMaxLength()).isEqualTo(200);
        });
        for (int limit : new int[]{201, 300, 1000}) {
            runner.withPropertyValues("cking.quiz.judge.reason-max-length=" + limit)
                    .run(context -> assertThat(context).as("reason limit %s", limit).hasFailed());
        }
    }

    @Test
    void 기본값은_채택한_초기_설정이고_API_key와_단가가_없어도_기동한다() {
        runner.run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(QuizJudge.class);
            OpenAiJudgeProperties properties = context.getBean(OpenAiJudgeProperties.class);
            assertThat(properties.getApiKey()).isEmpty();
            assertThat(properties.getBaseUrl()).isEqualTo("https://api.openai.com/v1");
            assertThat(properties.getModel()).isEqualTo("gpt-6.1-sol");
            assertThat(properties.getReasoningEffort()).isEqualTo("medium");
            assertThat(properties.maxOutputTokensFor(3)).isEqualTo(8192);
            assertThat(properties.maxOutputTokensFor(5)).isEqualTo(12288);
            assertThat(properties.getConnectTimeout()).isEqualTo(Duration.ofSeconds(2));
            assertThat(properties.getReadTimeout()).isEqualTo(Duration.ofSeconds(60));
            assertThat(properties.getExecutionTimeout()).isEqualTo(Duration.ofSeconds(150));
            assertThat(properties.getHttp().getMaxAttempts()).isEqualTo(2);
            assertThat(properties.getHttp().getRetryBackoff()).isEqualTo(Duration.ofSeconds(1));
            assertThat(properties.getHttp().getRetryAfterMax()).isEqualTo(Duration.ofSeconds(5));
            assertThat(properties.getReasonMaxLength()).isEqualTo(200);
            assertThat(properties.getPricing().isConfigured()).isFalse();
        });
    }

    @Test
    void 비운_단가_설정은_미설정으로_본다() {
        runner.withPropertyValues(
                        "cking.quiz.judge.pricing.version=",
                        "cking.quiz.judge.pricing.input-usd-per-million-tokens=",
                        "cking.quiz.judge.pricing.cached-input-usd-per-million-tokens=",
                        "cking.quiz.judge.pricing.output-usd-per-million-tokens=")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(OpenAiJudgeProperties.class).getPricing().isConfigured()).isFalse();
                });
    }

    @Test
    void 단가는_버전과_세_값을_함께_설정해야_한다() {
        runner.withPropertyValues(
                        "cking.quiz.judge.pricing.version=pricing-v1",
                        "cking.quiz.judge.pricing.input-usd-per-million-tokens=2",
                        "cking.quiz.judge.pricing.cached-input-usd-per-million-tokens=0.5",
                        "cking.quiz.judge.pricing.output-usd-per-million-tokens=10")
                .run(context -> assertThat(context).hasNotFailed());

        String[][] invalid = {
                {"cking.quiz.judge.pricing.version=pricing-v1"},
                {"cking.quiz.judge.pricing.input-usd-per-million-tokens=2"},
                {"cking.quiz.judge.pricing.version=pricing-v1",
                        "cking.quiz.judge.pricing.input-usd-per-million-tokens=2",
                        "cking.quiz.judge.pricing.cached-input-usd-per-million-tokens=0.5"},
                {"cking.quiz.judge.pricing.version=pricing-v1",
                        "cking.quiz.judge.pricing.input-usd-per-million-tokens=-1",
                        "cking.quiz.judge.pricing.cached-input-usd-per-million-tokens=0.5",
                        "cking.quiz.judge.pricing.output-usd-per-million-tokens=10"}};
        for (String[] properties : invalid) {
            runner.withPropertyValues(properties).run(context -> {
                assertThat(context).as(String.join(", ", properties)).hasFailed();
                assertThat(context.getStartupFailure()).hasStackTraceContaining("pricing");
            });
        }
    }

    @Test
    void reasoning_effort는_medium만_허용한다() {
        runner.withPropertyValues("cking.quiz.judge.reasoning-effort=medium")
                .run(context -> assertThat(context).hasNotFailed());

        for (String effort : new String[] {"medum", "high", "low", "MEDIUM"}) {
            runner.withPropertyValues("cking.quiz.judge.reasoning-effort=" + effort).run(context -> {
                assertThat(context).as(effort).hasFailed();
                assertThat(context.getStartupFailure()).hasStackTraceContaining("reasoningEffortSupported");
            });
        }
    }

    @Test
    void 잘못된_실행_설정이면_기동하지_않는다() {
        String[] invalid = {
                "cking.quiz.judge.model= ",
                "cking.quiz.judge.reasoning-effort=",
                "cking.quiz.judge.base-url=",
                "cking.quiz.judge.max-output-tokens.three-questions=0",
                "cking.quiz.judge.max-output-tokens.five-questions=-1",
                "cking.quiz.judge.connect-timeout=PT0S",
                "cking.quiz.judge.read-timeout=-PT1S",
                "cking.quiz.judge.read-timeout=PT151S",
                "cking.quiz.judge.execution-timeout=PT0S",
                "cking.quiz.judge.http.max-attempts=0",
                "cking.quiz.judge.http.max-attempts=3",
                "cking.quiz.judge.http.retry-backoff=PT0S",
                "cking.quiz.judge.http.retry-after-max=PT0S",
                "cking.quiz.judge.http.retry-after-max=PT151S",
                "cking.quiz.judge.reason-max-length=0",
                "cking.quiz.judge.reason-max-length=1001"};
        for (String property : invalid) {
            runner.withPropertyValues(property)
                    .run(context -> assertThat(context).as(property).hasFailed());
        }
    }
}
