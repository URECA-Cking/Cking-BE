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

import static org.assertj.core.api.Assertions.assertThat;

class EventCacheTest {

    private LettuceConnectionFactory connectionFactory;
    private EventCache eventCache;
    private Instant now;

    @BeforeEach
    void setUp() {
        connectionFactory = new LettuceConnectionFactory("localhost", 6379);
        connectionFactory.afterPropertiesSet();
        RedisTemplate<String, CachedEvent> redisTemplate = new RedisTemplate<>();
        redisTemplate.setConnectionFactory(connectionFactory);
        redisTemplate.setKeySerializer(new StringRedisSerializer());
        redisTemplate.afterPropertiesSet();
        now = Instant.parse("2026-09-15T00:00:00Z");
        eventCache = new EventCache(redisTemplate, Clock.fixed(now, ZoneOffset.UTC));
    }

    @AfterEach
    void tearDown() {
        connectionFactory.getConnection().serverCommands().flushDb();
        connectionFactory.destroy();
    }

    @Test
    void 저장한_이벤트를_다시_조회할_수_있다() {
        CachedEvent event = new CachedEvent(1L, 2L, "여름 이벤트", "설명",
                now, now.plus(Duration.ofDays(5)), 3, EventStatus.OPEN);

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
                now.minus(Duration.ofDays(10)), now.minus(Duration.ofDays(1)), 3, EventStatus.CLOSED);

        eventCache.save(ended);

        assertThat(eventCache.find(3L)).isEmpty();
    }

    @Test
    void TTL은_endAt까지_남은_시간이다() {
        CachedEvent event = new CachedEvent(2L, 2L, "여름 이벤트", "설명",
                now, now.plus(Duration.ofHours(3)), 3, EventStatus.OPEN);

        eventCache.save(event);

        Long ttlSeconds = connectionFactory.getConnection().keyCommands()
                .ttl("cache:event:2".getBytes());
        assertThat(ttlSeconds).isLessThanOrEqualTo(Duration.ofHours(3).toSeconds());
        assertThat(ttlSeconds).isGreaterThan(Duration.ofHours(2).toSeconds());
    }
}
