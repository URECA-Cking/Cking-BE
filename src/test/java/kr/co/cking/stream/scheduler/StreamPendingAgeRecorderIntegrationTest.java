package kr.co.cking.stream.scheduler;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StringRedisTemplate;

class StreamPendingAgeRecorderIntegrationTest {

    private static final String SPEND_STREAM = "stream:pending-age-test:spend";
    private static final String EARN_STREAM = "stream:pending-age-test:earn";
    private static final String COMMON_EARN_STREAM = "stream:pending-age-test:common-earn";
    private static final String GROUP = "cg:pending-age-test";
    private static final long NOW = 1_800_000_000_000L;

    private LettuceConnectionFactory connectionFactory;
    private StringRedisTemplate redisTemplate;
    private SimpleMeterRegistry meterRegistry;
    private StreamPendingAgeRecorder recorder;

    @BeforeEach
    void setUp() {
        connectionFactory = new LettuceConnectionFactory("localhost", 6379);
        connectionFactory.afterPropertiesSet();
        redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();
        cleanUpKeys();
        meterRegistry = new SimpleMeterRegistry();
        recorder = new StreamPendingAgeRecorder(
                redisTemplate, Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC), meterRegistry,
                SPEND_STREAM, GROUP, EARN_STREAM, GROUP, COMMON_EARN_STREAM, GROUP);
    }

    @AfterEach
    void tearDown() {
        cleanUpKeys();
        connectionFactory.destroy();
    }

    @Test
    void 기록_전에는_NaN이다() {
        assertThat(age(SPEND_STREAM)).isNaN();
    }

    @Test
    void PEL에서_가장_오래된_메시지의_나이를_초로_기록한다() {
        createGroup(SPEND_STREAM);
        add(SPEND_STREAM, NOW - 900_000);
        add(SPEND_STREAM, NOW - 5_000);
        read(SPEND_STREAM, 2);

        recorder.record();

        assertThat(age(SPEND_STREAM)).isEqualTo(900.0);
    }

    @Test
    void ACK한_메시지는_빼고_남은_PEL에서_가장_오래된_것을_본다() {
        createGroup(SPEND_STREAM);
        add(SPEND_STREAM, NOW - 900_000);
        add(SPEND_STREAM, NOW - 5_000);
        RecordId delivered = read(SPEND_STREAM, 1).get(0);
        redisTemplate.opsForStream().acknowledge(SPEND_STREAM, GROUP, delivered);
        read(SPEND_STREAM, 1);

        recorder.record();

        assertThat(age(SPEND_STREAM)).isEqualTo(5.0);
    }

    @Test
    void 아직_전달되지_않은_메시지만_있으면_0이다() {
        createGroup(SPEND_STREAM);
        add(SPEND_STREAM, NOW - 900_000);

        recorder.record();

        assertThat(age(SPEND_STREAM)).isZero();
    }

    @Test
    void PEL이_비면_0이다() {
        createGroup(EARN_STREAM);
        add(EARN_STREAM, NOW - 900_000);
        RecordId delivered = read(EARN_STREAM, 1).get(0);
        redisTemplate.opsForStream().acknowledge(EARN_STREAM, GROUP, delivered);

        recorder.record();

        assertThat(age(EARN_STREAM)).isZero();
    }

    @Test
    void 그룹이_없어_조회에_실패하면_NaN이다() {
        add(COMMON_EARN_STREAM, NOW - 1_000);

        recorder.record();

        assertThat(age(COMMON_EARN_STREAM)).isNaN();
    }

    @Test
    void Stream마다_따로_기록한다() {
        createGroup(SPEND_STREAM);
        createGroup(EARN_STREAM);
        createGroup(COMMON_EARN_STREAM);
        add(SPEND_STREAM, NOW - 30_000);
        read(SPEND_STREAM, 1);

        recorder.record();

        assertThat(age(SPEND_STREAM)).isEqualTo(30.0);
        assertThat(age(EARN_STREAM)).isZero();
        assertThat(age(COMMON_EARN_STREAM)).isZero();
    }

    private double age(String stream) {
        return meterRegistry.get(StreamPendingAgeRecorder.METRIC).tag("stream", stream).gauge().value();
    }

    private void createGroup(String stream) {
        redisTemplate.opsForStream().add(MapRecord.create(stream, Map.of("init", "1")).withId(RecordId.of(1, 0)));
        redisTemplate.opsForStream().createGroup(stream, ReadOffset.from("1-0"), GROUP);
    }

    private void add(String stream, long timestamp) {
        redisTemplate.opsForStream().add(MapRecord.create(stream, Map.of("k", "v")).withId(RecordId.of(timestamp, 0)));
    }

    @SuppressWarnings("unchecked")
    private List<RecordId> read(String stream, int count) {
        List<MapRecord<String, Object, Object>> records = redisTemplate.opsForStream().read(
                Consumer.from(GROUP, "c1"), StreamReadOptions.empty().count(count),
                StreamOffset.create(stream, ReadOffset.lastConsumed()));
        return records.stream().map(MapRecord::getId).toList();
    }

    private void cleanUpKeys() {
        redisTemplate.delete(List.of(SPEND_STREAM, EARN_STREAM, COMMON_EARN_STREAM));
    }
}
