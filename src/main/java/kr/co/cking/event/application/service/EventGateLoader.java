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
 * <p>ponytail: cutoff 키까지 유실된 상태에서 stale한 OPEN 조회값이 겹치는 경우(Redis 전체 유실 직후 마감 시작)는
 * 막지 못한다. 그 경우 다음 틱의 마감 시작이 barrier로 Gate를 다시 닫는다.
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
}
