package kr.co.cking.event.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StringRedisTemplate;

import kr.co.cking.event.application.config.EntryRedisKeys;

@SpringBootTest(properties = "cking.entry.stream-key=stream:ticket-deducted:barrier-test")
class EventCutoffBarrierTest {

    private static final Long EVENT_ID = 91001L;
    private static final String STREAM_KEY = "stream:ticket-deducted:barrier-test";

    @Autowired
    private EventCutoffBarrier eventCutoffBarrier;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @BeforeEach
    @AfterEach
    void cleanUp() {
        redisTemplate.delete(List.of(
                EntryRedisKeys.status(EVENT_ID),
                EntryRedisKeys.cutoff(EVENT_ID),
                STREAM_KEY
        ));
    }

    @Test
    void close는_Gate를_막고_스트림_마지막_ID를_cutoff로_확정한다() {
        redisTemplate.opsForValue().set(EntryRedisKeys.status(EVENT_ID), "OPEN");
        MapRecord<String, String, String> record = MapRecord.create(STREAM_KEY, java.util.Map.of("eventId", "1"));
        var recordId = redisTemplate.opsForStream().add(record);

        String cutoff = eventCutoffBarrier.close(EVENT_ID);

        assertThat(cutoff).isEqualTo(recordId.getValue());
        assertThat(redisTemplate.opsForValue().get(EntryRedisKeys.status(EVENT_ID))).isNotEqualTo("OPEN");
    }

    @Test
    void 스트림에_아무_응모도_없으면_cutoff는_0_0이다() {
        redisTemplate.opsForValue().set(EntryRedisKeys.status(EVENT_ID), "OPEN");

        String cutoff = eventCutoffBarrier.close(EVENT_ID);

        assertThat(cutoff).isEqualTo("0-0");
    }

    @Test
    void 재호출해도_같은_cutoff를_반환한다() {
        redisTemplate.opsForValue().set(EntryRedisKeys.status(EVENT_ID), "OPEN");
        String first = eventCutoffBarrier.close(EVENT_ID);

        redisTemplate.opsForStream().add(MapRecord.create(STREAM_KEY, java.util.Map.of("eventId", "1")));
        String second = eventCutoffBarrier.close(EVENT_ID);

        assertThat(second).isEqualTo(first);
    }
}
