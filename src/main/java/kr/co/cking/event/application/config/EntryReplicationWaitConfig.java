package kr.co.cking.event.application.config;

import io.micrometer.core.instrument.MeterRegistry;
import kr.co.cking.event.application.service.EntryReplicationWait;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

/** 응모 성공 응답 전 복제 노드 확인(#674)을 켤 때만 만든다. 켤 때는 제한 시간을 반드시 지정한다. */
@Configuration
public class EntryReplicationWaitConfig {

    @Bean
    @ConditionalOnProperty(name = "cking.entry.replication-wait.enabled", havingValue = "true")
    public EntryReplicationWait entryReplicationWait(
            LettuceConnectionFactory lettuceConnectionFactory,
            @Value("${cking.entry.replication-wait.replicas:1}") int replicas,
            @Value("${cking.entry.replication-wait.timeout-ms}") long timeoutMillis,
            MeterRegistry meterRegistry
    ) {
        return new EntryReplicationWait(lettuceConnectionFactory, replicas, timeoutMillis, meterRegistry);
    }
}
