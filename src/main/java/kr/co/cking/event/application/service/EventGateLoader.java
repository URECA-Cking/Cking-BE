package kr.co.cking.event.application.service;

import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import kr.co.cking.event.application.config.EntryRedisKeys;
import kr.co.cking.event.domain.Event;
import kr.co.cking.event.repository.EventEntryAggregate;
import kr.co.cking.event.repository.EventEntryRepository;

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
    private final EventEntryRepository eventEntryRepository;

    public EventGateLoader(
            StringRedisTemplate redisTemplate,
            @Qualifier("eventGateLoadLuaScript") DefaultRedisScript<Long> eventGateLoadLuaScript,
            EventEntryRepository eventEntryRepository
    ) {
        this.redisTemplate = redisTemplate;
        this.eventGateLoadLuaScript = eventGateLoadLuaScript;
        this.eventEntryRepository = eventEntryRepository;
    }

    public void load(Event event) {
        Long eventId = event.getEventId();
        List<String> keys = List.of(
                EntryRedisKeys.status(eventId),
                EntryRedisKeys.endAt(eventId),
                EntryRedisKeys.cutoff(eventId),
                EntryRedisKeys.entryTotal(eventId),
                EntryRedisKeys.entrants(eventId)
        );
        redisTemplate.execute(eventGateLoadLuaScript, keys, (Object[]) buildArgv(eventId, event));
    }

    // Gate 키가 이미 있으면(정상 운영 중 대부분의 호출) DB를 조회하지 않는다 - 실시간 응모
    // 현황 집계는 Gate가 처음 열릴 때만 초기화하면 되고, Lua가 KEYS[1] 존재 여부를 다시
    // 확인하므로 이 사전 확인과 실제 초기화 사이의 경합에도 이중 초기화는 발생하지 않는다.
    private String[] buildArgv(Long eventId, Event event) {
        List<String> argv = new ArrayList<>();
        argv.add(String.valueOf(event.getEndAt().toEpochMilli()));

        if (Boolean.FALSE.equals(redisTemplate.hasKey(EntryRedisKeys.status(eventId)))) {
            List<EventEntryAggregate> aggregates = eventEntryRepository.aggregateByEvent(eventId);
            long total = aggregates.stream().mapToLong(EventEntryAggregate::getTicketCount).sum();
            argv.add(String.valueOf(total));
            for (EventEntryAggregate aggregate : aggregates) {
                argv.add(String.valueOf(aggregate.getMemberId()));
                argv.add(String.valueOf(aggregate.getTicketCount()));
            }
        } else {
            // Lua가 이 경로에서는 ARGV[2] 이후를 읽지 않지만, 인덱스 정합을 위해 자리는 채워 둔다.
            argv.add("0");
        }

        return argv.toArray(new String[0]);
    }

    /** 마감 전이(CLOSING·CLOSED) 이벤트의 Gate를 CLOSED로 덮어쓴다. 마감 중인 Gate는 항상 닫혀 있어야 하므로 반복 호출해도 안전하다. */
    public void close(Long eventId) {
        redisTemplate.opsForValue().set(EntryRedisKeys.status(eventId), "CLOSED");
    }
}
