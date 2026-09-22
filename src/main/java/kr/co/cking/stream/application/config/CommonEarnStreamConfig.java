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

import kr.co.cking.stream.presentation.CommonEarnStreamListener;

/**
 * {@code stream:common-ticket-earned}을 {@code cg:common-ticket-earn} Consumer
 * Group으로 소비한다(이슈 #219, {@code EarnStreamConfig}와 동일 계약).
 */
@Configuration
public class CommonEarnStreamConfig {

    private static final String CONSUMER_NAME = "common-earn-consumer-1";

    @Value("${cking.ticket.common-earn-stream-key:stream:common-ticket-earned}")
    private String streamKey;

    @Value("${cking.ticket.common-earn-consumer-group:cg:common-ticket-earn}")
    private String consumerGroup;

    @Bean(initMethod = "start", destroyMethod = "stop")
    public StreamMessageListenerContainer<String, MapRecord<String, String, String>> commonEarnStreamListenerContainer(
            RedisConnectionFactory connectionFactory,
            StringRedisTemplate redisTemplate,
            CommonEarnStreamListener commonEarnStreamListener,
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

        container.receive(
                Consumer.from(consumerGroup, CONSUMER_NAME),
                StreamOffset.create(streamKey, ReadOffset.lastConsumed()),
                commonEarnStreamListener
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
