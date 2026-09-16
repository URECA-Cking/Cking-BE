package kr.co.cking.event;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 같은 Redis DB를 다른 팀·다른 용도(stream:ticket-deducted 등)는 물론 애플리케이션이
 * 이미 만들어둔 실제 cache:event:* 캐시와도 공유할 수 있으므로, 운영과 다른 전용
 * prefix({@value #TEST_KEY_PREFIX})를 써서 이 테스트가 만든 키만 확실히 지운다.
 */
class EventCacheTest {

    private static final String TEST_KEY_PREFIX = "cache:event:test:";
    private static final String KEY_PATTERN = TEST_KEY_PREFIX + "*";

    private LettuceConnectionFactory connectionFactory;
    private RedisTemplate<String, CachedEvent> redisTemplate;
    private EventCache eventCache;
    private Instant now;

    @BeforeEach
    void setUp() {
        connectionFactory = new LettuceConnectionFactory("localhost", 6379);
        connectionFactory.afterPropertiesSet();
        redisTemplate = new RedisTemplate<>();
        redisTemplate.setConnectionFactory(connectionFactory);
        redisTemplate.setKeySerializer(new StringRedisSerializer());
        redisTemplate.afterPropertiesSet();
        now = Instant.parse("2026-09-15T00:00:00Z");
        eventCache = new EventCache(redisTemplate, Clock.fixed(now, ZoneOffset.UTC), TEST_KEY_PREFIX);
    }

    @AfterEach
    void tearDown() {
        Set<String> keys = redisTemplate.keys(KEY_PATTERN);
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
        connectionFactory.destroy();
    }

    @Test
    void 저장한_이벤트를_다시_조회할_수_있다() {
        CachedEvent event = new CachedEvent(1L, 2L, "여름 이벤트", "설명",
                now, now.plus(Duration.ofDays(5)), 3, "WEIGHTED", EventStatus.OPEN);

        eventCache.save(event);

        assertThat(eventCache.find(1L)).contains(event);
    }

    @Test
    void 없는_이벤트는_빈값이다() {
        Optional<CachedEvent> found = eventCache.find(999L);

        assertThat(found).isEmpty();
    }

    @Test
    void 이미_종료된_이벤트는_캐싱하지_않는다() {
        CachedEvent ended = new CachedEvent(3L, 2L, "종료 이벤트", "설명",
                now.minus(Duration.ofDays(10)), now.minus(Duration.ofDays(1)), 3, "WEIGHTED", EventStatus.CLOSED);

        eventCache.save(ended);

        assertThat(eventCache.find(3L)).isEmpty();
    }

    @Test
    void TTL은_5초를_넘지_않는다() {
        CachedEvent event = new CachedEvent(2L, 2L, "여름 이벤트", "설명",
                now, now.plus(Duration.ofHours(3)), 3, "WEIGHTED", EventStatus.OPEN);

        eventCache.save(event);

        Long ttlSeconds = connectionFactory.getConnection().keyCommands()
                .ttl((TEST_KEY_PREFIX + "2").getBytes());
        assertThat(ttlSeconds).isLessThanOrEqualTo(5L);
        assertThat(ttlSeconds).isGreaterThan(0L);
    }

    @Test
    void endAt이_5초보다_가까우면_그때까지만_캐싱한다() {
        CachedEvent event = new CachedEvent(4L, 2L, "곧 끝나는 이벤트", "설명",
                now.minus(Duration.ofMinutes(1)), now.plus(Duration.ofSeconds(2)), 3, "WEIGHTED", EventStatus.OPEN);

        eventCache.save(event);

        Long ttlSeconds = connectionFactory.getConnection().keyCommands()
                .ttl((TEST_KEY_PREFIX + "4").getBytes());
        assertThat(ttlSeconds).isLessThanOrEqualTo(2L);
        assertThat(ttlSeconds).isGreaterThan(0L);
    }

    @Test
    void evict하면_더이상_조회되지_않는다() {
        CachedEvent event = new CachedEvent(5L, 2L, "여름 이벤트", "설명",
                now, now.plus(Duration.ofDays(5)), 3, "WEIGHTED", EventStatus.OPEN);
        eventCache.save(event);

        eventCache.evict(5L);

        assertThat(eventCache.find(5L)).isEmpty();
    }
}
