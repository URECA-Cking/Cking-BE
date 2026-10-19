package kr.co.cking.stream.scheduler;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import kr.co.cking.stream.application.config.StreamConsumerNames;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamInfo.XInfoConsumer;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StringRedisTemplate;

/** 떠난 소비자만 지우고, 대기 메시지가 있거나 최근에 읽은 소비자와 이 서버의 소비자는 남기는지 검증한다. */
class StreamConsumerCleanupSchedulerIntegrationTest {

    private static final String SPEND_STREAM = "stream:consumer-cleanup-test:spend";
    private static final String EARN_STREAM = "stream:consumer-cleanup-test:earn";
    private static final String COMMON_EARN_STREAM = "stream:consumer-cleanup-test:common-earn";
    private static final String GROUP = "cg:consumer-cleanup-test";
    private static final Duration IDLE = Duration.ofMillis(300);

    private LettuceConnectionFactory connectionFactory;
    private StringRedisTemplate redisTemplate;
    private StreamConsumerCleanupScheduler scheduler;

    @BeforeEach
    void setUp() {
        connectionFactory = new LettuceConnectionFactory("localhost", 6379);
        connectionFactory.afterPropertiesSet();
        redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();
        cleanUpKeys();
        scheduler = new StreamConsumerCleanupScheduler(redisTemplate, new StreamConsumerNames("self"),
                SPEND_STREAM, GROUP, EARN_STREAM, GROUP, COMMON_EARN_STREAM, GROUP, IDLE);
    }

    @AfterEach
    void tearDown() {
        cleanUpKeys();
        connectionFactory.destroy();
    }

    @Test
    void 대기_메시지가_없고_오래_쉰_소비자만_지운다() throws InterruptedException {
        redisTemplate.opsForStream().createGroup(SPEND_STREAM, ReadOffset.from("0"), GROUP);
        readAndAck("spend-departed-empty");
        read("spend-departed-pending");
        readAndAck("spend-self");
        Thread.sleep(IDLE.toMillis() + 200);
        readAndAck("spend-recent");

        scheduler.cleanUp();

        assertThat(consumerNames(SPEND_STREAM))
                .containsExactlyInAnyOrder("spend-departed-pending", "spend-self", "spend-recent");
    }

    @Test
    void Stream이나_그룹이_없어도_실패하지_않는다() {
        scheduler.cleanUp();

        assertThat(redisTemplate.hasKey(SPEND_STREAM)).isFalse();
    }

    private void readAndAck(String consumer) {
        read(consumer).forEach(record -> redisTemplate.opsForStream().acknowledge(SPEND_STREAM, GROUP, record.getId()));
    }

    private List<org.springframework.data.redis.connection.stream.MapRecord<String, Object, Object>> read(String consumer) {
        redisTemplate.opsForStream().add(SPEND_STREAM, Map.of("type", "test"));
        return redisTemplate.opsForStream().read(Consumer.from(GROUP, consumer), StreamReadOptions.empty().count(1),
                StreamOffset.create(SPEND_STREAM, ReadOffset.lastConsumed()));
    }

    private List<String> consumerNames(String stream) {
        return redisTemplate.opsForStream().consumers(stream, GROUP).stream().map(XInfoConsumer::consumerName).toList();
    }

    private void cleanUpKeys() {
        redisTemplate.delete(List.of(SPEND_STREAM, EARN_STREAM, COMMON_EARN_STREAM));
    }
}
