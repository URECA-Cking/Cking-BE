package kr.co.cking.event.application.service;

import java.util.List;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import kr.co.cking.event.application.config.EntryRedisKeys;
import kr.co.cking.event.domain.Event;

/**
 * DB의 OPEN 이벤트를 응모 Gate(event:status, event:endat)로 적재한다. 키가 없을 때만 쓰고,
 * 마감 barrier가 cutoff를 확정한 이벤트는 건너뛴다(event-gate-load.lua). 여러 번 호출해도 안전하다.
 *
 * <p>cutoff 키까지 유실된 상태에서 stale한 OPEN 조회값이 겹치면 CLOSING 이벤트의 Gate가 다시 열릴 수 있다.
 * 마감 전이 커밋 직후 리스너와 스케줄러(CLOSING 재조회)가 {@link #close}로 다시 닫는다.
 */
@Component
public class EventGateLoader {

    private final StringRedisTemplate redisTemplate;
    private final DefaultRedisScript<Long> eventGateLoadLuaScript;

    public EventGateLoader(
            StringRedisTemplate redisTemplate,
            @Qualifier("eventGateLoadLuaScript") DefaultRedisScript<Long> eventGateLoadLuaScript
    ) {
        this.redisTemplate = redisTemplate;
        this.eventGateLoadLuaScript = eventGateLoadLuaScript;
    }

    public void load(Event event) {
        Long eventId = event.getEventId();
        redisTemplate.execute(
                eventGateLoadLuaScript,
                List.of(EntryRedisKeys.status(eventId), EntryRedisKeys.endAt(eventId), EntryRedisKeys.cutoff(eventId)),
                String.valueOf(event.getEndAt().toEpochMilli())
        );
    }

    /** 마감 전이(CLOSING·CLOSED) 이벤트의 Gate를 CLOSED로 덮어쓴다. 마감 중인 Gate는 항상 닫혀 있어야 하므로 반복 호출해도 안전하다. */
    public void close(Long eventId) {
        redisTemplate.opsForValue().set(EntryRedisKeys.status(eventId), "CLOSED");
    }
}
