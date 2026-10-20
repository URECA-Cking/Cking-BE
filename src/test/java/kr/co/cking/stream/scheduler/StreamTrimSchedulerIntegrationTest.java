package kr.co.cking.stream.scheduler;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.Limit;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 모든 그룹이 처리를 끝낸 앞쪽 메시지만 지우고, PEL·아직 전달되지 않은 메시지는 남기는지 검증한다.
 * MINID ~는 100건 묶음 단위로 지우므로 묶음보다 많이 넣는다.
 */
@ExtendWith(OutputCaptureExtension.class)
class StreamTrimSchedulerIntegrationTest {

    private static final String SPEND_STREAM = "stream:trim-test:spend";
    private static final String EARN_STREAM = "stream:trim-test:earn";
    private static final String COMMON_EARN_STREAM = "stream:trim-test:common-earn";
    private static final String GROUP = "cg:trim-test";
    private static final String OTHER_GROUP = "cg:trim-test-other";
    private static final int TOTAL = 300;

    private LettuceConnectionFactory connectionFactory;
    private StringRedisTemplate redisTemplate;
    private StreamTrimScheduler scheduler;

    @BeforeEach
    void setUp() {
        connectionFactory = new LettuceConnectionFactory("localhost", 6379);
        connectionFactory.afterPropertiesSet();
        redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();
        cleanUpKeys();
        scheduler = new StreamTrimScheduler(redisTemplate, SPEND_STREAM, EARN_STREAM, COMMON_EARN_STREAM);
    }

    @AfterEach
    void tearDown() {
        cleanUpKeys();
        connectionFactory.destroy();
    }

    @Test
    void PEL에_남은_메시지와_그_뒤는_남기고_앞쪽만_지운다() {
        List<RecordId> ids = add(SPEND_STREAM, TOTAL);
        createGroup(SPEND_STREAM, GROUP);
        List<RecordId> read = read(SPEND_STREAM, GROUP, TOTAL);
        RecordId pending = ids.get(250);
        read.stream().filter(id -> !id.equals(pending)).forEach(id -> ack(SPEND_STREAM, GROUP, id));

        scheduler.trim();

        assertThat(redisTemplate.opsForStream().size(SPEND_STREAM)).isLessThan(TOTAL);
        assertThat(rangeFrom(SPEND_STREAM, pending)).hasSize(TOTAL - 250);
        assertThat(redisTemplate.opsForStream().range(SPEND_STREAM, Range.closed(pending.getValue(), pending.getValue())))
                .hasSize(1);
    }

    @Test
    void 아직_전달되지_않은_메시지는_지우지_않는다() {
        List<RecordId> ids = add(SPEND_STREAM, TOTAL);
        createGroup(SPEND_STREAM, GROUP);
        read(SPEND_STREAM, GROUP, 250).forEach(id -> ack(SPEND_STREAM, GROUP, id));

        scheduler.trim();

        assertThat(redisTemplate.opsForStream().size(SPEND_STREAM)).isLessThan(TOTAL);
        assertThat(rangeFrom(SPEND_STREAM, ids.get(249))).hasSize(TOTAL - 249);
    }

    @Test
    void 그룹이_여럿이면_가장_뒤처진_그룹을_기준으로_지운다() {
        List<RecordId> ids = add(SPEND_STREAM, TOTAL);
        createGroup(SPEND_STREAM, GROUP);
        createGroup(SPEND_STREAM, OTHER_GROUP);
        read(SPEND_STREAM, GROUP, TOTAL).forEach(id -> ack(SPEND_STREAM, GROUP, id));
        read(SPEND_STREAM, OTHER_GROUP, 150).forEach(id -> ack(SPEND_STREAM, OTHER_GROUP, id));

        scheduler.trim();

        assertThat(redisTemplate.opsForStream().size(SPEND_STREAM)).isLessThan(TOTAL);
        assertThat(rangeFrom(SPEND_STREAM, ids.get(149))).hasSize(TOTAL - 149);
    }

    @Test
    void 그룹이_없으면_지우지_않는다() {
        add(SPEND_STREAM, TOTAL);

        scheduler.trim();

        assertThat(redisTemplate.opsForStream().size(SPEND_STREAM)).isEqualTo(TOTAL);
    }

    @Test
    void 세_Stream을_모두_정리하고_Stream이_없어도_실패하지_않는다() {
        for (String stream : List.of(EARN_STREAM, COMMON_EARN_STREAM)) {
            add(stream, TOTAL);
            createGroup(stream, GROUP);
            read(stream, GROUP, TOTAL).forEach(id -> ack(stream, GROUP, id));
        }

        scheduler.trim();

        assertThat(redisTemplate.hasKey(SPEND_STREAM)).isFalse();
        assertThat(redisTemplate.opsForStream().size(EARN_STREAM)).isLessThan(TOTAL);
        assertThat(redisTemplate.opsForStream().size(COMMON_EARN_STREAM)).isLessThan(TOTAL);
    }

    @Test
    void Stream이_아닌_키면_경고를_남기고_다른_Stream은_계속_정리한다(CapturedOutput output) {
        redisTemplate.opsForValue().set(SPEND_STREAM, "not-a-stream");
        add(EARN_STREAM, TOTAL);
        createGroup(EARN_STREAM, GROUP);
        read(EARN_STREAM, GROUP, TOTAL).forEach(id -> ack(EARN_STREAM, GROUP, id));

        scheduler.trim();

        assertThat(output).contains("처리가 끝난 Stream 메시지를 지우지 못했습니다", "stream=" + SPEND_STREAM);
        assertThat(redisTemplate.opsForStream().size(EARN_STREAM)).isLessThan(TOTAL);
    }

    private List<RecordId> add(String stream, int count) {
        List<RecordId> ids = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            ids.add(redisTemplate.opsForStream().add(stream, Map.of("seq", String.valueOf(i))));
        }
        return ids;
    }

    private void createGroup(String stream, String group) {
        redisTemplate.opsForStream().createGroup(stream, ReadOffset.from("0"), group);
    }

    private List<RecordId> read(String stream, String group, int count) {
        List<MapRecord<String, Object, Object>> records = redisTemplate.opsForStream().read(
                Consumer.from(group, "trim-test"), StreamReadOptions.empty().count(count),
                StreamOffset.create(stream, ReadOffset.lastConsumed()));
        return records.stream().map(MapRecord::getId).toList();
    }

    private void ack(String stream, String group, RecordId id) {
        redisTemplate.opsForStream().acknowledge(stream, group, id);
    }

    private List<MapRecord<String, Object, Object>> rangeFrom(String stream, RecordId from) {
        return redisTemplate.opsForStream().range(stream, Range.rightUnbounded(Range.Bound.inclusive(from.getValue())),
                Limit.unlimited());
    }

    private void cleanUpKeys() {
        redisTemplate.delete(List.of(SPEND_STREAM, EARN_STREAM, COMMON_EARN_STREAM));
    }
}
