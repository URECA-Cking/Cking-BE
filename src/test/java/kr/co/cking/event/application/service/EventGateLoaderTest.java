package kr.co.cking.event.application.service;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import kr.co.cking.event.application.config.EntryRedisKeys;
import kr.co.cking.event.domain.Event;

class EventGateLoaderTest {

    @Test
    @SuppressWarnings("unchecked")
    void endat을_먼저_status를_나중에_없을_때만_적재한다() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(ops);
        Event event = mock(Event.class);
        Instant endAt = Instant.parse("2026-09-30T00:00:00Z");
        when(event.getEventId()).thenReturn(7L);
        when(event.getEndAt()).thenReturn(endAt);

        new EventGateLoader(redisTemplate).load(event);

        InOrder order = inOrder(ops);
        order.verify(ops).setIfAbsent(EntryRedisKeys.endAt(7L), String.valueOf(endAt.toEpochMilli()));
        order.verify(ops).setIfAbsent(EntryRedisKeys.status(7L), "OPEN");
        verifyNoMoreInteractions(ops);
    }
}
