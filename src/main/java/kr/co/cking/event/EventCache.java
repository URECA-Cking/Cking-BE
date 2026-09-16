package kr.co.cking.event;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.util.Optional;

/**
 * 취합v1.5.4 §13.3(NFR-02/FR-03 확정) 기준: TTL은 5초로 고정하고, 어떤 경우에도
 * endAt을 넘지 않는다. 이 캐시는 정확성을 책임지지 않는다 — 마감 임박 트래픽에 대한
 * 순수 DB 부하 완화용이며, 정확성은 상태 표시 규칙과 {@link #evict} 무효화가 담당한다.
 * Event 상태 변경 시(EventCommandService 등) 반드시 {@link #evict}를 호출해야 한다.
 */
@Component
@RequiredArgsConstructor
public class EventCache {

    private static final String KEY_PREFIX = "cache:event:";
    private static final Duration MAX_TTL = Duration.ofSeconds(5);

    private final RedisTemplate<String, CachedEvent> redisTemplate;
    private final Clock clock;

    public Optional<CachedEvent> find(Long eventId) {
        return Optional.ofNullable(redisTemplate.opsForValue().get(key(eventId)));
    }

    public void save(CachedEvent event) {
        Duration untilEnd = Duration.between(clock.instant(), event.endAt());
        if (untilEnd.isNegative() || untilEnd.isZero()) {
            return;
        }
        Duration ttl = untilEnd.compareTo(MAX_TTL) < 0 ? untilEnd : MAX_TTL;
        redisTemplate.opsForValue().set(key(event.eventId()), event, ttl);
    }

    /** Event 상태가 바뀌었을 때 호출해서 stale 캐시를 지운다. */
    public void evict(Long eventId) {
        redisTemplate.delete(key(eventId));
    }

    private String key(Long eventId) {
        return KEY_PREFIX + eventId;
    }
}
