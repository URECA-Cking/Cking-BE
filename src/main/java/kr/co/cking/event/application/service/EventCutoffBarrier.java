package kr.co.cking.event.application.service;

import java.util.List;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import kr.co.cking.event.application.config.EntryRedisKeys;

/**
 * 이벤트 마감 시 신규 응모 Gate 차단과 cutoff(응모 Stream 마지막 ID) 확정을
 * Redis 원자처리(barrier)로 묶는다. 이미 확정된 이벤트는 재호출해도 같은
 * cutoff를 반환한다(재시도·서버 재기동에도 새 cutoff가 생기지 않음).
 */
@Component
public class EventCutoffBarrier {

    // entry-spend.lua Gate는 값이 'OPEN'인지만 본다. 기존 EntrySpendServiceIntegrationTest
    // 관례와 맞춰 'CLOSED'를 쓴다 - CLOSING이라는 별도 Gate 값을 새로 만들지 않는다.
    private static final String CLOSED_GATE_VALUE = "CLOSED";

    private final StringRedisTemplate redisTemplate;
    private final DefaultRedisScript<String> eventCloseBarrierLuaScript;

    @Value("${cking.entry.stream-key:stream:ticket-deducted}")
    private String entryStreamKey;

    public EventCutoffBarrier(
            StringRedisTemplate redisTemplate,
            @Qualifier("eventCloseBarrierLuaScript") DefaultRedisScript<String> eventCloseBarrierLuaScript
    ) {
        this.redisTemplate = redisTemplate;
        this.eventCloseBarrierLuaScript = eventCloseBarrierLuaScript;
    }

    /** Gate를 차단하고 cutoff streamId를 확정해 반환한다. 이미 확정돼 있으면 그 값을 그대로 반환한다. */
    public String close(Long eventId) {
        return redisTemplate.execute(
                eventCloseBarrierLuaScript,
                List.of(
                        EntryRedisKeys.status(eventId),
                        EntryRedisKeys.cutoff(eventId),
                        entryStreamKey
                ),
                CLOSED_GATE_VALUE
        );
    }
}
