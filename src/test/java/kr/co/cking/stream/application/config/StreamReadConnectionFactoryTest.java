package kr.co.cking.stream.application.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.SslVerifyMode;
import io.lettuce.core.api.StatefulConnection;
import io.lettuce.core.resource.ClientResources;
import io.lettuce.core.resource.DefaultClientResources;
import java.time.Duration;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettucePoolingClientConfiguration;

class StreamReadConnectionFactoryTest {

    @Test
    void 공용_연결의_TLS_풀_옵션을_그대로_쓰고_명령_제한_시간만_바꾼다() {
        GenericObjectPoolConfig<StatefulConnection<?, ?>> poolConfig = new GenericObjectPoolConfig<>();
        poolConfig.setMaxTotal(16);
        ClientOptions clientOptions = ClientOptions.builder().build();
        ClientResources clientResources = DefaultClientResources.create();
        try {
            LettuceClientConfiguration shared = LettucePoolingClientConfiguration.builder()
                    .poolConfig(poolConfig)
                    .useSsl().verifyPeer(SslVerifyMode.CA).and()
                    .clientOptions(clientOptions)
                    .clientResources(clientResources)
                    .clientName("cking")
                    .commandTimeout(Duration.ofSeconds(1))
                    .build();

            LettuceClientConfiguration stream = StreamReadConnectionFactory.withCommandTimeout(shared, Duration.ofSeconds(6));

            assertThat(stream).isInstanceOf(LettucePoolingClientConfiguration.class);
            assertThat(((LettucePoolingClientConfiguration) stream).getPoolConfig()).isSameAs(poolConfig);
            assertThat(stream.isUseSsl()).isTrue();
            assertThat(stream.getVerifyMode()).isEqualTo(SslVerifyMode.CA);
            assertThat(stream.isStartTls()).isFalse();
            assertThat(stream.getClientOptions()).containsSame(clientOptions);
            assertThat(stream.getClientResources()).containsSame(clientResources);
            assertThat(stream.getClientName()).contains("cking");
            assertThat(stream.getCommandTimeout()).isEqualTo(Duration.ofSeconds(6));
            assertThat(shared.getCommandTimeout()).isEqualTo(Duration.ofSeconds(1));
        } finally {
            clientResources.shutdown();
        }
    }

    @Test
    void TLS를_쓰지_않는_공용_연결은_TLS_없이_복사한다() {
        LettuceClientConfiguration shared = LettuceClientConfiguration.builder()
                .commandTimeout(Duration.ofSeconds(1))
                .build();

        LettuceClientConfiguration stream = StreamReadConnectionFactory.withCommandTimeout(shared, Duration.ofSeconds(6));

        assertThat(stream).isNotInstanceOf(LettucePoolingClientConfiguration.class);
        assertThat(stream.isUseSsl()).isFalse();
        assertThat(stream.getCommandTimeout()).isEqualTo(Duration.ofSeconds(6));
    }
}
