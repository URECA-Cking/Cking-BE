package kr.co.cking.stream.application.config;

import java.time.Duration;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettucePoolingClientConfiguration;
import org.springframework.stereotype.Component;

/**
 * Stream 소비자의 막힘 읽기(XREADGROUP BLOCK) 전용 연결.
 * 공용 연결의 명령 제한 시간(spring.data.redis.timeout)은 막힘 읽기 대기보다 짧아 그 연결로는 읽기가 실패한다(#743).
 * 접속·TLS·인증·풀 설정은 공용 연결에서 그대로 가져오고 명령 제한 시간만 바꾼다.
 * RedisConnectionFactory 빈으로 두면 Spring Boot가 공용 연결을 만들지 않으므로 빈으로 노출하지 않는다.
 */
@Component
public class StreamReadConnectionFactory implements DisposableBean {

    private final LettuceConnectionFactory connectionFactory;

    public StreamReadConnectionFactory(
            LettuceConnectionFactory sharedFactory,
            @Value("${cking.stream.read-command-timeout}") Duration commandTimeout
    ) {
        if (sharedFactory.getClusterConfiguration() != null || sharedFactory.getSentinelConfiguration() != null) {
            throw new IllegalStateException("Stream 읽기 전용 연결은 주 노드 하나에 붙는 구성에서만 만든다.");
        }
        this.connectionFactory = new LettuceConnectionFactory(
                sharedFactory.getStandaloneConfiguration(),
                withCommandTimeout(sharedFactory.getClientConfiguration(), commandTimeout));
        this.connectionFactory.afterPropertiesSet();
        this.connectionFactory.start();
    }

    public RedisConnectionFactory get() {
        return connectionFactory;
    }

    @Override
    public void destroy() {
        connectionFactory.destroy();
    }

    static LettuceClientConfiguration withCommandTimeout(LettuceClientConfiguration source, Duration commandTimeout) {
        LettuceClientConfiguration.LettuceClientConfigurationBuilder builder =
                source instanceof LettucePoolingClientConfiguration pooling
                        ? LettucePoolingClientConfiguration.builder().poolConfig(pooling.getPoolConfig())
                        : LettuceClientConfiguration.builder();
        if (source.isUseSsl()) {
            LettuceClientConfiguration.LettuceSslClientConfigurationBuilder ssl = builder.useSsl().verifyPeer(source.getVerifyMode());
            if (source.isStartTls()) {
                ssl.startTls();
            }
            builder = ssl.and();
        }
        source.getClientResources().ifPresent(builder::clientResources);
        source.getClientOptions().ifPresent(builder::clientOptions);
        source.getClientName().ifPresent(builder::clientName);
        source.getReadFrom().ifPresent(builder::readFrom);
        source.getRedisCredentialsProviderFactory().ifPresent(builder::redisCredentialsProviderFactory);
        return builder.commandTimeout(commandTimeout)
                .shutdownTimeout(source.getShutdownTimeout())
                .shutdownQuietPeriod(source.getShutdownQuietPeriod())
                .build();
    }
}
