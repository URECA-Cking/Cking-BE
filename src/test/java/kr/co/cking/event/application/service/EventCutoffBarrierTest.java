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
                EntryRedisKeys.entryTotal(EVENT_ID),
                EntryRedisKeys.entrants(EVENT_ID),
                STREAM_KEY
        ));
    }

    @Test
    void close는_Gate를_막고_EVENT_ENTRY_CLOSED_barrier를_XADD해_그_ID를_cutoff로_확정한다() {
        redisTemplate.opsForValue().set(EntryRedisKeys.status(EVENT_ID), "OPEN");
        MapRecord<String, String, String> record = MapRecord.create(STREAM_KEY, java.util.Map.of("eventId", "1"));
        redisTemplate.opsForStream().add(record);

        String cutoff = eventCutoffBarrier.close(EVENT_ID);

        List<MapRecord<String, Object, Object>> messages = redisTemplate.opsForStream().range(STREAM_KEY, org.springframework.data.domain.Range.unbounded());
        MapRecord<String, Object, Object> barrierMessage = messages.get(messages.size() - 1);
        assertThat(barrierMessage.getId().getValue()).isEqualTo(cutoff);
        assertThat(barrierMessage.getValue()).containsEntry("type", "EVENT_ENTRY_CLOSED");
        assertThat(redisTemplate.opsForValue().get(EntryRedisKeys.status(EVENT_ID))).isNotEqualTo("OPEN");
    }

    @Test
    void 스트림에_아무_응모도_없어도_barrier_메시지로_cutoff가_확정된다() {
        redisTemplate.opsForValue().set(EntryRedisKeys.status(EVENT_ID), "OPEN");

        String cutoff = eventCutoffBarrier.close(EVENT_ID);

        assertThat(cutoff).isNotEqualTo("0-0");
        assertThat(redisTemplate.opsForStream().size(STREAM_KEY)).isEqualTo(1L);
    }

    @Test
    void 재호출해도_같은_cutoff를_반환하고_barrier를_다시_추가하지_않는다() {
        redisTemplate.opsForValue().set(EntryRedisKeys.status(EVENT_ID), "OPEN");
        String first = eventCutoffBarrier.close(EVENT_ID);

        String second = eventCutoffBarrier.close(EVENT_ID);

        assertThat(second).isEqualTo(first);
        assertThat(redisTemplate.opsForStream().size(STREAM_KEY)).isEqualTo(1L);
    }

    @Test
    void 새_cutoff_확정_시_실시간_응모_현황_집계_키에_만료를_건다() {
        redisTemplate.opsForValue().set(EntryRedisKeys.status(EVENT_ID), "OPEN");
        redisTemplate.opsForValue().set(EntryRedisKeys.entryTotal(EVENT_ID), "5");
        redisTemplate.opsForHash().put(EntryRedisKeys.entrants(EVENT_ID), "1", "5");

        eventCutoffBarrier.close(EVENT_ID);

        assertThat(redisTemplate.getExpire(EntryRedisKeys.entryTotal(EVENT_ID))).isGreaterThan(0);
        assertThat(redisTemplate.getExpire(EntryRedisKeys.entrants(EVENT_ID))).isGreaterThan(0);
    }

    @Test
    void 집계_키가_없어도_cutoff_확정은_실패하지_않는다() {
        redisTemplate.opsForValue().set(EntryRedisKeys.status(EVENT_ID), "OPEN");

        String cutoff = eventCutoffBarrier.close(EVENT_ID);

        assertThat(cutoff).isNotBlank();
    }
}
