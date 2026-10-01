package kr.co.cking.abuse.config;

import static org.assertj.core.api.Assertions.assertThat;

import kr.co.cking.abuse.domain.AbuseType;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class AbuseConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(AbuseConfiguration.class);

    @Test
    void 비활성화하면_Rule_설정이_없어도_기동한다() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(AbuseProperties.class);
            assertThat(context.getBean(AbuseProperties.class).enabled()).isFalse();
        });
    }

    @Test
    void 활성화하면_모든_Rule_설정을_바인딩한다() {
        contextRunner
                .withPropertyValues(enabledProperties())
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(AbuseProperties.class);
                    AbuseProperties properties = context.getBean(AbuseProperties.class);
                    assertThat(properties.enabled()).isTrue();
                    assertThat(properties.windowPolicy().requestIdRotationWindow().toSeconds()).isEqualTo(10L);
                    assertThat(properties.cooldownTtl(AbuseType.FAILURE_BURST).toSeconds()).isEqualTo(20L);
                });
    }

    @Test
    void 활성화하고_Rule_설정이_누락되면_기동에_실패한다() {
        contextRunner
                .withPropertyValues("cking.abuse.enabled=true")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void 활성화하고_Window가_0이면_기동에_실패한다() {
        String[] properties = enabledProperties();
        properties[1] = "cking.abuse.mission-request-burst.window=PT0S";

        contextRunner
                .withPropertyValues(properties)
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void 활성화하고_Window가_음수이면_기동에_실패한다() {
        String[] properties = enabledProperties();
        properties[1] = "cking.abuse.mission-request-burst.window=-PT1S";

        contextRunner
                .withPropertyValues(properties)
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void 활성화하고_Threshold가_0이면_기동에_실패한다() {
        String[] properties = enabledProperties();
        properties[2] = "cking.abuse.mission-request-burst.threshold=0";

        contextRunner
                .withPropertyValues(properties)
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void 활성화하고_Threshold가_음수이면_기동에_실패한다() {
        String[] properties = enabledProperties();
        properties[2] = "cking.abuse.mission-request-burst.threshold=-1";

        contextRunner
                .withPropertyValues(properties)
                .run(context -> assertThat(context).hasFailed());
    }

    private String[] enabledProperties() {
        return new String[] {
                "cking.abuse.enabled=true",
                "cking.abuse.mission-request-burst.window=PT10S",
                "cking.abuse.mission-request-burst.threshold=5",
                "cking.abuse.duplicate-mission-burst.window=PT10S",
                "cking.abuse.duplicate-mission-burst.threshold=5",
                "cking.abuse.entry-request-burst.window=PT10S",
                "cking.abuse.entry-request-burst.threshold=5",
                "cking.abuse.insufficient-balance-burst.window=PT10S",
                "cking.abuse.insufficient-balance-burst.threshold=5",
                "cking.abuse.insufficient-balance-burst.consecutive-threshold=3",
                "cking.abuse.request-id-rotation.window=PT10S",
                "cking.abuse.request-id-rotation.distinct-threshold=3",
                "cking.abuse.rapid-earn-and-spend.max-delay=PT5S",
                "cking.abuse.rapid-earn-and-spend.window=PT10S",
                "cking.abuse.rapid-earn-and-spend.threshold=3",
                "cking.abuse.failure-burst.window=PT10S",
                "cking.abuse.failure-burst.threshold=5",
                "cking.abuse.failure-burst.consecutive-threshold=3",
                "cking.abuse.failure-burst.distinct-type-threshold=2"
        };
    }
}
