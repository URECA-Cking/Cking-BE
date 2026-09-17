package kr.co.cking.event.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.core.StringRedisTemplate;

@SpringBootTest(properties = {
        "cking.entry.stream-key=stream:ticket-deducted:drain-test",
        "cking.entry.history-consumer-group=cg:ticket-history:drain-test"
})
class EventDrainCheckerTest {

    private static final String STREAM_KEY = "stream:ticket-deducted:drain-test";
    private static final String GROUP = "cg:ticket-history:drain-test";
    private static final String CONSUMER = "drain-test-consumer";

    @Autowired
    private EventDrainChecker eventDrainChecker;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @BeforeEach
    @AfterEach
    void cleanUp() {
        redisTemplate.delete(STREAM_KEY);
    }

    @Test
    void 그룹이_없으면_Drain되지_않은_것으로_본다() {
        assertThat(eventDrainChecker.isDrained(1L, "0-0")).isFalse();
    }

    @Test
    void cutoff까지_읽고_ACK했으면_Drain된_것으로_본다() {
        RecordId recordId = redisTemplate.opsForStream()
                .add(MapRecord.create(STREAM_KEY, Map.of("eventId", "1")));
        createGroupFromZero();

        redisTemplate.opsForStream().read(
                Consumer.from(GROUP, CONSUMER),
                StreamOffset.create(STREAM_KEY, ReadOffset.lastConsumed())
        );
        redisTemplate.opsForStream().acknowledge(STREAM_KEY, GROUP, recordId);

        assertThat(eventDrainChecker.isDrained(1L, recordId.getValue())).isTrue();
    }

    @Test
    void 읽었지만_ACK하지_않았으면_Drain되지_않은_것으로_본다() {
        RecordId recordId = redisTemplate.opsForStream()
                .add(MapRecord.create(STREAM_KEY, Map.of("eventId", "1")));
        createGroupFromZero();

        redisTemplate.opsForStream().read(
                Consumer.from(GROUP, CONSUMER),
                StreamOffset.create(STREAM_KEY, ReadOffset.lastConsumed())
        );

        assertThat(eventDrainChecker.isDrained(1L, recordId.getValue())).isFalse();
    }

    @Test
    void cutoff_이후에_추가된_메시지는_아직_읽지_않아도_Drain된_것으로_본다() {
        RecordId beforeCutoff = redisTemplate.opsForStream()
                .add(MapRecord.create(STREAM_KEY, Map.of("eventId", "1")));
        createGroupFromZero();
        redisTemplate.opsForStream().read(
                Consumer.from(GROUP, CONSUMER),
                StreamOffset.create(STREAM_KEY, ReadOffset.lastConsumed())
        );
        redisTemplate.opsForStream().acknowledge(STREAM_KEY, GROUP, beforeCutoff);

        redisTemplate.opsForStream().add(MapRecord.create(STREAM_KEY, Map.of("eventId", "2")));

        assertThat(eventDrainChecker.isDrained(1L, beforeCutoff.getValue())).isTrue();
    }

    @Test
    void 다른_이벤트의_PEL_메시지는_이_이벤트의_Drain_판정에_영향을_주지_않는다() {
        redisTemplate.opsForStream().add(MapRecord.create(STREAM_KEY, Map.of("eventId", "2")));
        RecordId myCutoff = redisTemplate.opsForStream()
                .add(MapRecord.create(STREAM_KEY, Map.of("eventId", "1")));
        createGroupFromZero();

        redisTemplate.opsForStream().read(
                Consumer.from(GROUP, CONSUMER),
                StreamOffset.create(STREAM_KEY, ReadOffset.lastConsumed())
        );
        redisTemplate.opsForStream().acknowledge(STREAM_KEY, GROUP, myCutoff);
        // eventId=2 메시지는 ACK하지 않아 그룹 전체 PEL에는 남아있다.

        assertThat(eventDrainChecker.isDrained(1L, myCutoff.getValue())).isTrue();
    }

    @Test
    void 이_이벤트의_PEL_메시지가_cutoff_이하에_남아있으면_Drain되지_않은_것으로_본다() {
        RecordId myMessage = redisTemplate.opsForStream()
                .add(MapRecord.create(STREAM_KEY, Map.of("eventId", "1")));
        RecordId cutoff = redisTemplate.opsForStream()
                .add(MapRecord.create(STREAM_KEY, Map.of("eventId", "1")));
        createGroupFromZero();

        redisTemplate.opsForStream().read(
                Consumer.from(GROUP, CONSUMER),
                StreamOffset.create(STREAM_KEY, ReadOffset.lastConsumed())
        );
        redisTemplate.opsForStream().acknowledge(STREAM_KEY, GROUP, cutoff);
        // myMessage(eventId=1)는 ACK하지 않고 남겨둔다.

        assertThat(eventDrainChecker.isDrained(1L, cutoff.getValue())).isFalse();
    }

    private void createGroupFromZero() {
        redisTemplate.execute((org.springframework.data.redis.core.RedisCallback<String>) connection ->
                connection.streamCommands().xGroupCreate(
                        STREAM_KEY.getBytes(StandardCharsets.UTF_8),
                        GROUP,
                        ReadOffset.from("0"),
                        true
                )
        );
    }
}
