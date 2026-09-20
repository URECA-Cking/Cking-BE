package kr.co.cking.event.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import kr.co.cking.event.application.config.EntryRedisKeys;
import kr.co.cking.event.domain.Event;

@SpringBootTest
class EventGateLoaderIntegrationTest {

    private static final Long EVENT_ID = 90101L;

    @Autowired
    private EventGateLoader eventGateLoader;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @BeforeEach
    @AfterEach
    void cleanUp() {
        redisTemplate.delete(List.of(EntryRedisKeys.status(EVENT_ID), EntryRedisKeys.endAt(EVENT_ID)));
    }

    private Event event(Instant endAt) {
        Event event = mock(Event.class);
        when(event.getEventId()).thenReturn(EVENT_ID);
        when(event.getEndAt()).thenReturn(endAt);
        return event;
    }

    @Test
    void 키가_없으면_OPEN과_endat을_적재한다() {
        Instant endAt = Instant.parse("2099-01-01T00:00:00Z");

        eventGateLoader.load(event(endAt));

        assertThat(redisTemplate.opsForValue().get(EntryRedisKeys.status(EVENT_ID))).isEqualTo("OPEN");
        assertThat(redisTemplate.opsForValue().get(EntryRedisKeys.endAt(EVENT_ID)))
                .isEqualTo(String.valueOf(endAt.toEpochMilli()));
    }

    @Test
    void 마감_barrier가_닫은_Gate는_재적재해도_다시_열리지_않는다() {
        redisTemplate.opsForValue().set(EntryRedisKeys.status(EVENT_ID), "CLOSED");

        eventGateLoader.load(event(Instant.parse("2099-01-01T00:00:00Z")));

        assertThat(redisTemplate.opsForValue().get(EntryRedisKeys.status(EVENT_ID))).isEqualTo("CLOSED");
    }
}
