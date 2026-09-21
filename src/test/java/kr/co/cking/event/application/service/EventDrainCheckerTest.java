package kr.co.cking.event.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.core.RedisCallback;
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

    @Autowired
    private JdbcClient jdbcClient;

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
    void cutoff_이후_메시지가_PEL에_남아있어도_Drain된_것으로_본다() {
        RecordId beforeCutoff = redisTemplate.opsForStream()
                .add(MapRecord.create(STREAM_KEY, Map.of("eventId", "1")));
        createGroupFromZero();
        redisTemplate.opsForStream().read(
                Consumer.from(GROUP, CONSUMER),
                StreamOffset.create(STREAM_KEY, ReadOffset.lastConsumed())
        );
        redisTemplate.opsForStream().acknowledge(STREAM_KEY, GROUP, beforeCutoff);

        redisTemplate.opsForStream().add(MapRecord.create(STREAM_KEY, Map.of("eventId", "2")));
        redisTemplate.opsForStream().read(
                Consumer.from(GROUP, CONSUMER),
                StreamOffset.create(STREAM_KEY, ReadOffset.lastConsumed())
        );
        // cutoff 이후 eventId=2 메시지는 ACK하지 않아 PEL에 남겨둔다.

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

    @Test
    void 앞선_만건이_다른_이벤트여도_그_뒤의_대상_이벤트_PEL을_확인한다() {
        for (int index = 0; index < 10_000; index++) {
            redisTemplate.opsForStream().add(MapRecord.create(STREAM_KEY, Map.of("eventId", "2")));
        }
        RecordId cutoff = redisTemplate.opsForStream()
                .add(MapRecord.create(STREAM_KEY, Map.of("eventId", "1")));
        createGroupFromZero();

        redisTemplate.opsForStream().read(
                Consumer.from(GROUP, CONSUMER),
                StreamReadOptions.empty().count(10_001),
                StreamOffset.create(STREAM_KEY, ReadOffset.lastConsumed())
        );

        assertThat(eventDrainChecker.isDrained(1L, cutoff.getValue())).isFalse();
    }

    @Test
    void PEL이_양끝에만_남고_사이가_전부_ACK돼도_대상_이벤트_PEL을_ID_단위로_확인한다() {
        redisTemplate.opsForStream().add(MapRecord.create(STREAM_KEY, Map.of("eventId", "2")));
        RecordId middleEnd = null;
        for (int index = 0; index < 2_000; index++) {
            middleEnd = redisTemplate.opsForStream().add(MapRecord.create(STREAM_KEY, Map.of("eventId", "1")));
        }
        RecordId cutoff = redisTemplate.opsForStream()
                .add(MapRecord.create(STREAM_KEY, Map.of("eventId", "1")));
        createGroupFromZero();
        redisTemplate.opsForStream().read(
                Consumer.from(GROUP, CONSUMER),
                StreamReadOptions.empty().count(3_000),
                StreamOffset.create(STREAM_KEY, ReadOffset.lastConsumed())
        );
        // 첫 메시지(eventId=2)와 cutoff(eventId=1)만 PEL에 남기고 사이 2,000건은 ACK한다.
        redisTemplate.opsForStream().range(STREAM_KEY, org.springframework.data.domain.Range.closed("-", middleEnd.getValue()))
                .stream().skip(1)
                .forEach(record -> redisTemplate.opsForStream().acknowledge(STREAM_KEY, GROUP, record.getId()));

        long before = xrangeCalls();
        boolean drained = eventDrainChecker.isDrained(1L, cutoff.getValue());

        assertThat(drained).isFalse();
        // 범위 XRANGE 1회가 아니라 PEL 2건을 각각 ID 단위로 조회해야 사이 2,000건을 읽지 않는다.
        assertThat(xrangeCalls() - before).isEqualTo(2);
    }

    private long xrangeCalls() {
        Properties stats = redisTemplate.execute((RedisCallback<Properties>) connection ->
                connection.serverCommands().info("commandstats"));
        String line = stats == null ? null : stats.getProperty("cmdstat_xrange");
        return line == null ? 0 : Long.parseLong(line.replaceAll("^calls=(\\d+).*", "$1"));
    }

    @Test
    void cutoff_이하에_미해결_SPEND_Dead_Stream이_있으면_Drain되지_않은_것으로_본다() {
        DeadStreamFixture fixture = persistEvent();
        try {
            RecordId cutoff = redisTemplate.opsForStream()
                    .add(MapRecord.create(STREAM_KEY, Map.of("eventId", String.valueOf(fixture.eventId()))));
            createGroupFromZero();
            redisTemplate.opsForStream().read(
                    Consumer.from(GROUP, CONSUMER),
                    StreamOffset.create(STREAM_KEY, ReadOffset.lastConsumed())
            );
            redisTemplate.opsForStream().acknowledge(STREAM_KEY, GROUP, cutoff);
            insertUnresolvedDeadStream(fixture.eventId(), cutoff.getValue());

            assertThat(eventDrainChecker.isDrained(fixture.eventId(), cutoff.getValue())).isFalse();
        } finally {
            deleteFixture(fixture);
        }
    }

    @Test
    void cutoff_이후의_미해결_SPEND_Dead_Stream은_Drain을_막지_않는다() {
        DeadStreamFixture fixture = persistEvent();
        try {
            RecordId cutoff = redisTemplate.opsForStream()
                    .add(MapRecord.create(STREAM_KEY, Map.of("eventId", String.valueOf(fixture.eventId()))));
            createGroupFromZero();
            redisTemplate.opsForStream().read(
                    Consumer.from(GROUP, CONSUMER),
                    StreamOffset.create(STREAM_KEY, ReadOffset.lastConsumed())
            );
            redisTemplate.opsForStream().acknowledge(STREAM_KEY, GROUP, cutoff);

            RecordId afterCutoff = redisTemplate.opsForStream()
                    .add(MapRecord.create(STREAM_KEY, Map.of("eventId", String.valueOf(fixture.eventId()))));
            insertUnresolvedDeadStream(fixture.eventId(), afterCutoff.getValue());

            assertThat(eventDrainChecker.isDrained(fixture.eventId(), cutoff.getValue())).isTrue();
        } finally {
            deleteFixture(fixture);
        }
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

    private DeadStreamFixture persistEvent() {
        jdbcClient.sql("INSERT INTO member (name, role) VALUES ('Drain 테스트 사용자', 'USER')").update();
        long memberId = lastInsertId();
        jdbcClient.sql("INSERT INTO creator (member_id, name) VALUES (:memberId, 'Drain 테스트 Creator')")
                .param("memberId", memberId)
                .update();
        long creatorId = lastInsertId();
        jdbcClient.sql("""
                        INSERT INTO event (
                            creator_id, title, start_at, end_at, winner_count, draw_method,
                            status, created_by, request_id
                        ) VALUES (
                            :creatorId, 'Drain 테스트 Event', :startAt, :endAt, 1, 'WEIGHTED',
                            'CLOSING', :memberId, :requestId
                        )
                        """)
                .param("creatorId", creatorId)
                .param("startAt", Instant.parse("2026-09-10T00:00:00Z"))
                .param("endAt", Instant.parse("2026-09-20T00:00:00Z"))
                .param("memberId", memberId)
                .param("requestId", UUID.randomUUID().toString())
                .update();
        return new DeadStreamFixture(memberId, creatorId, lastInsertId());
    }

    private long lastInsertId() {
        return jdbcClient.sql("SELECT LAST_INSERT_ID()")
                .query((resultSet, rowNumber) -> resultSet.getLong(1))
                .single();
    }

    private void insertUnresolvedDeadStream(long eventId, String sourceStreamId) {
        jdbcClient.sql("""
                        INSERT INTO dead_stream_message (
                            source_stream_id, stream_type, payload, event_id, retry_count, resolution_status
                        ) VALUES (
                            :sourceStreamId, 'SPEND', '{}', :eventId, 3, 'UNRESOLVED'
                        )
                        """)
                .param("sourceStreamId", sourceStreamId)
                .param("eventId", eventId)
                .update();
    }

    private void deleteFixture(DeadStreamFixture fixture) {
        jdbcClient.sql("DELETE FROM dead_stream_message WHERE event_id = :eventId")
                .param("eventId", fixture.eventId())
                .update();
        jdbcClient.sql("DELETE FROM event WHERE event_id = :eventId")
                .param("eventId", fixture.eventId())
                .update();
        jdbcClient.sql("DELETE FROM creator WHERE creator_id = :creatorId")
                .param("creatorId", fixture.creatorId())
                .update();
        jdbcClient.sql("DELETE FROM member WHERE member_id = :memberId")
                .param("memberId", fixture.memberId())
                .update();
    }

    private record DeadStreamFixture(long memberId, long creatorId, long eventId) {
    }
}
