package kr.co.cking.event.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import kr.co.cking.event.application.dto.EventCounts;

/**
 * 상태별 개수({@code GET /api/events/counts}) 캐시. {@link EventListCache}와 같은 정책이다 —
 * TTL 5초, 가장 임박한 OPEN 이벤트의 endAt을 넘기지 않아 진행 중→종료 전환이 굳지 않는다.
 * 값은 {@code upcoming,inProgress,closed} 문자열이고, 읽을 수 없으면 cache miss로 본다.
 */
@Component
public class EventCountsCache {

    private static final Duration MAX_TTL = Duration.ofSeconds(5);

    private final StringRedisTemplate redisTemplate;
    private final Clock clock;
    private final String key;

    public EventCountsCache(StringRedisTemplate redisTemplate, Clock clock,
                            @Value("${cache.event-counts.key:cache:event:counts}") String key) {
        this.redisTemplate = redisTemplate;
        this.clock = clock;
        this.key = key;
    }

    public Optional<EventCounts> find() {
        try {
            String value = redisTemplate.opsForValue().get(key);
            if (value == null) {
                return Optional.empty();
            }
            String[] parts = value.split(",");
            long upcoming = Long.parseLong(parts[0]);
            long inProgress = Long.parseLong(parts[1]);
            long closed = Long.parseLong(parts[2]);
            return Optional.of(new EventCounts(upcoming + inProgress + closed, upcoming, inProgress, closed));
        } catch (RuntimeException exception) {
            return Optional.empty();
        }
    }

    /** earliestOpenEndAt이 없으면(진행 중 이벤트 없음) 5초로 캐싱한다. */
    public void save(EventCounts counts, Instant earliestOpenEndAt) {
        Duration ttl = MAX_TTL;
        if (earliestOpenEndAt != null) {
            Duration untilEnd = Duration.between(clock.instant(), earliestOpenEndAt);
            if (untilEnd.compareTo(ttl) < 0) {
                ttl = untilEnd;
            }
        }
        if (ttl.isNegative() || ttl.isZero()) {
            return;
        }
        redisTemplate.opsForValue().set(key, counts.upcoming() + "," + counts.inProgress() + "," + counts.closed(), ttl);
    }
}
