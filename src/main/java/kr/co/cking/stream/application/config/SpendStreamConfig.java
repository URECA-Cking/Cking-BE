package kr.co.cking.stream.application.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.stream.StreamMessageListenerContainer;
import org.springframework.data.redis.stream.StreamMessageListenerContainer.StreamMessageListenerContainerOptions;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

import kr.co.cking.stream.presentation.SpendStreamListener;

/**
 * {@code stream:ticket-deducted}를 {@code cg:ticket-history} Consumer Group으로 소비한다.
 * FR-09 MVP는 이 그룹의 concurrency를 1로 고정한다.
 */
@Configuration
public class SpendStreamConfig {

    private static final String CONSUMER_NAME = "spend-consumer-1";

    @Value("${cking.entry.stream-key:stream:ticket-deducted}")
    private String streamKey;

    @Value("${cking.entry.history-consumer-group:cg:ticket-history}")
    private String consumerGroup;

    @Bean(initMethod = "start", destroyMethod = "stop")
    public StreamMessageListenerContainer<String, MapRecord<String, String, String>> spendStreamListenerContainer(
            RedisConnectionFactory connectionFactory,
            StringRedisTemplate redisTemplate,
            SpendStreamListener spendStreamListener,
            ApplicationShutdownState shutdownState
    ) {
        ensureConsumerGroup(redisTemplate);

        StreamMessageListenerContainerOptions<String, MapRecord<String, String, String>> options =
                StreamMessageListenerContainerOptions.builder()
                        .pollTimeout(Duration.ofSeconds(2))
                        .errorHandler(new StreamListenerErrorHandler(shutdownState))
                        .build();

        StreamMessageListenerContainer<String, MapRecord<String, String, String>> container =
                StreamMessageListenerContainer.create(connectionFactory, options);

        // PEL 재수신(XCLAIM)·Dead Stream 승격은 SpendStreamPelRecoveryScheduler가
        // 별도로 주기 실행한다(EARN 쪽 EarnStreamPelRecoveryScheduler·이슈 #43과 동일 패턴).
        container.receive(
                Consumer.from(consumerGroup, CONSUMER_NAME),
                StreamOffset.create(streamKey, ReadOffset.lastConsumed()),
                spendStreamListener
        );

        return container;
    }

    private void ensureConsumerGroup(StringRedisTemplate redisTemplate) {
        try {
            redisTemplate.execute((RedisCallback<String>) connection ->
                    connection.streamCommands().xGroupCreate(
                            streamKey.getBytes(StandardCharsets.UTF_8),
                            consumerGroup,
                            ReadOffset.from("0"),
                            true
                    )
            );
        } catch (DataAccessException e) {
            Throwable rootCause = e.getMostSpecificCause();
            if (rootCause.getMessage() == null || !rootCause.getMessage().contains("BUSYGROUP")) {
                throw e;
            }
        }
    }
}
