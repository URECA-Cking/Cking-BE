package kr.co.cking.stream.application.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.stream.StreamMessageListenerContainer;
import org.springframework.data.redis.stream.StreamMessageListenerContainer.StreamMessageListenerContainerOptions;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

import kr.co.cking.stream.presentation.SpendStreamListener;

/**
 * {@code stream:ticket-deducted}를 {@code cg:ticket-history} Consumer Group으로 소비한다.
 * 메시지 한 건 반영은 대부분 DB 왕복을 기다리는 시간이라 소비자 하나로는 서버당 초당 약 100건에서 멈춘다(#672).
 * 서버마다 소비자를 {@code cking.stream.spend.consumer-count}개 두어 기다림을 겹치고, 한 번에 읽는 수를
 * {@code cking.stream.spend.batch-size}로 제한해 밀린 메시지를 소비자 하나가 모두 가져가지 않게 한다.
 */
@Configuration
public class SpendStreamConfig {

    @Value("${cking.entry.stream-key:stream:ticket-deducted}")
    private String streamKey;

    @Value("${cking.entry.history-consumer-group:cg:ticket-history}")
    private String consumerGroup;

    @Value("${cking.stream.spend.consumer-count:4}")
    private int consumerCount;

    @Value("${cking.stream.spend.batch-size:10}")
    private int batchSize;

    @Bean(initMethod = "start", destroyMethod = "stop")
    public StreamMessageListenerContainer<String, MapRecord<String, String, String>> spendStreamListenerContainer(
            StreamReadConnectionFactory streamReadConnectionFactory,
            StringRedisTemplate redisTemplate,
            SpendStreamListener spendStreamListener,
            StreamConsumerNames consumerNames
    ) {
        if (consumerCount < 1 || batchSize < 1) {
            throw new IllegalStateException("SPEND 소비자 수와 묶음 크기는 1 이상이어야 합니다. consumerCount=%d, batchSize=%d"
                    .formatted(consumerCount, batchSize));
        }
        ensureConsumerGroup(redisTemplate);

        StreamMessageListenerContainerOptions<String, MapRecord<String, String, String>> options =
                StreamMessageListenerContainerOptions.builder()
                        .pollTimeout(Duration.ofSeconds(2))
                        .batchSize(batchSize)
                        .build();

        StreamMessageListenerContainer<String, MapRecord<String, String, String>> container =
                StreamMessageListenerContainer.create(streamReadConnectionFactory.get(), options);

        // PEL 재수신(XCLAIM)·Dead Stream 승격은 SpendStreamPelRecoveryScheduler가
        // 별도로 주기 실행한다(EARN 쪽 EarnStreamPelRecoveryScheduler·이슈 #43과 동일 패턴).
        // 구독마다 컨테이너 기본 Executor(SimpleAsyncTaskExecutor)의 스레드 하나가 읽고 반영한다.
        for (String consumerName : consumerNames.of("spend", consumerCount)) {
            container.register(
                    StreamReadRequests.keepAlive(container, streamKey, consumerGroup, consumerName,
                            () -> ensureConsumerGroup(redisTemplate)),
                    spendStreamListener
            );
        }

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
