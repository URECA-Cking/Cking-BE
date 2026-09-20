package kr.co.cking.event.application.service;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import kr.co.cking.event.application.config.EntryRedisKeys;
import kr.co.cking.event.domain.Event;
import lombok.RequiredArgsConstructor;

/**
 * DB의 OPEN 이벤트를 응모 Gate(event:status, event:endat)로 적재한다. 키가 없을 때만 쓰므로
 * 마감 barrier가 이미 CLOSED로 바꾼 Gate를 다시 열지 않고, 여러 번 호출해도 안전하다.
 */
@Component
@RequiredArgsConstructor
public class EventGateLoader {

    private static final String OPEN_GATE_VALUE = "OPEN";

    private final StringRedisTemplate redisTemplate;

    /** endat을 먼저 쓴다. Lua는 두 키가 모두 있어야 Gate를 인정하므로 status가 마지막에 들어가야 반쯤 열린 상태가 없다. */
    public void load(Event event) {
        Long eventId = event.getEventId();
        redisTemplate.opsForValue().setIfAbsent(
                EntryRedisKeys.endAt(eventId), String.valueOf(event.getEndAt().toEpochMilli()));
        redisTemplate.opsForValue().setIfAbsent(EntryRedisKeys.status(eventId), OPEN_GATE_VALUE);
    }
}
