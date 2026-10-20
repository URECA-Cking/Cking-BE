package kr.co.cking.stream.scheduler;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import kr.co.cking.stream.application.config.StreamConsumerNames;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamInfo.XInfoConsumer;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StringRedisTemplate;

/** 떠난 소비자만 지우고, 대기 메시지가 있거나 읽기를 시도 중인 소비자, 이 서버와 재처리 소비자는 남기는지 검증한다. */
@ExtendWith(OutputCaptureExtension.class)
class StreamConsumerCleanupSchedulerIntegrationTest {

    private static final String SPEND_STREAM = "stream:consumer-cleanup-test:spend";
    private static final String EARN_STREAM = "stream:consumer-cleanup-test:earn";
    private static final String COMMON_EARN_STREAM = "stream:consumer-cleanup-test:common-earn";
    private static final String GROUP = "cg:consumer-cleanup-test";
    private static final Duration IDLE = Duration.ofSeconds(1);

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
        readAndAck(SpendStreamPelRecoveryScheduler.RECOVERY_CONSUMER);
        // 운영 Listener처럼 메시지 없이 기다리는 읽기만 반복한다. 기준 시간의 두 배 동안 이어 간다.
        long until = System.currentTimeMillis() + IDLE.toMillis() * 2;
        while (System.currentTimeMillis() < until) {
            waitForMessage("spend-listening");
        }

        scheduler.cleanUp();

        assertThat(consumerNames(SPEND_STREAM)).containsExactlyInAnyOrder("spend-departed-pending", "spend-self",
                SpendStreamPelRecoveryScheduler.RECOVERY_CONSUMER, "spend-listening");
    }

    @Test
    void Stream이나_그룹이_없어도_실패하지_않는다(CapturedOutput output) {
        redisTemplate.opsForStream().add(EARN_STREAM, Map.of("type", "test"));

        scheduler.cleanUp();

        assertThat(redisTemplate.hasKey(SPEND_STREAM)).isFalse();
        assertThat(output).doesNotContain("Stream 소비자 정리에 실패했습니다");
    }

    @Test
    void Stream이_아닌_키면_경고를_남기고_다른_Stream은_계속_정리한다(CapturedOutput output) throws InterruptedException {
        redisTemplate.opsForValue().set(EARN_STREAM, "not-a-stream");
        redisTemplate.opsForStream().createGroup(SPEND_STREAM, ReadOffset.from("0"), GROUP);
        readAndAck("spend-departed-empty");
        Thread.sleep(IDLE.toMillis() * 2);

        scheduler.cleanUp();

        assertThat(output).contains("Stream 소비자 정리에 실패했습니다", "stream=" + EARN_STREAM);
        assertThat(consumerNames(SPEND_STREAM)).doesNotContain("spend-departed-empty");
    }

    private void readAndAck(String consumer) {
        read(consumer).forEach(record -> redisTemplate.opsForStream().acknowledge(SPEND_STREAM, GROUP, record.getId()));
    }

    private List<org.springframework.data.redis.connection.stream.MapRecord<String, Object, Object>> read(String consumer) {
        redisTemplate.opsForStream().add(SPEND_STREAM, Map.of("type", "test"));
        return redisTemplate.opsForStream().read(Consumer.from(GROUP, consumer), StreamReadOptions.empty().count(1),
                StreamOffset.create(SPEND_STREAM, ReadOffset.lastConsumed()));
    }

    private void waitForMessage(String consumer) {
        redisTemplate.opsForStream().read(Consumer.from(GROUP, consumer),
                StreamReadOptions.empty().count(1).block(Duration.ofMillis(100)),
                StreamOffset.create(SPEND_STREAM, ReadOffset.lastConsumed()));
    }

    private List<String> consumerNames(String stream) {
        return redisTemplate.opsForStream().consumers(stream, GROUP).stream().map(XInfoConsumer::consumerName).toList();
    }

    private void cleanUpKeys() {
        redisTemplate.delete(List.of(SPEND_STREAM, EARN_STREAM, COMMON_EARN_STREAM));
    }
}
