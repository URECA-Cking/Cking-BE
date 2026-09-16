package kr.co.cking.event;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.util.Optional;

/**
 * 이벤트 상세는 자주 조회되지만 응모기간 동안만 의미가 있어서, TTL을 endAt까지
 * 남은 시간으로 잡아 이벤트가 끝나면 캐시도 자연 만료되게 한다.
 */
@Component
@RequiredArgsConstructor
public class EventCache {

    private static final String KEY_PREFIX = "cache:event:";

    private final RedisTemplate<String, CachedEvent> redisTemplate;
    private final Clock clock;

    public Optional<CachedEvent> find(Long eventId) {
        return Optional.ofNullable(redisTemplate.opsForValue().get(key(eventId)));
    }

    public void save(CachedEvent event) {
        Duration ttl = Duration.between(clock.instant(), event.endAt());
        if (ttl.isNegative() || ttl.isZero()) {
            return;
        }
        redisTemplate.opsForValue().set(key(event.eventId()), event, ttl);
    }

    private String key(Long eventId) {
        return KEY_PREFIX + eventId;
    }
}
