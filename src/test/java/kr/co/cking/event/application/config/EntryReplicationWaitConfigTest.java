package kr.co.cking.event.application.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import kr.co.cking.event.application.service.EntryReplicationWait;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

class EntryReplicationWaitConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(EntryReplicationWaitConfig.class)
            .withBean(LettuceConnectionFactory.class, () -> new LettuceConnectionFactory(new RedisStandaloneConfiguration()))
            .withBean(MeterRegistry.class, SimpleMeterRegistry::new);

    @Test
    void 기본으로는_만들지_않는다() {
        runner.run(context -> assertThat(context).doesNotHaveBean(EntryReplicationWait.class));
    }

    @Test
    void 켜고_제한_시간을_주면_만든다() {
        runner.withPropertyValues(
                        "cking.entry.replication-wait.enabled=true",
                        "cking.entry.replication-wait.timeout-ms=50")
                .run(context -> assertThat(context).hasSingleBean(EntryReplicationWait.class));
    }

    @Test
    void 켜고_제한_시간을_주지_않으면_기동하지_않는다() {
        runner.withPropertyValues("cking.entry.replication-wait.enabled=true")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void 제한_시간_0은_무한_대기라_거부한다() {
        runner.withPropertyValues(
                        "cking.entry.replication-wait.enabled=true",
                        "cking.entry.replication-wait.timeout-ms=0")
                .run(context -> assertThat(context).hasFailed());
    }
}
