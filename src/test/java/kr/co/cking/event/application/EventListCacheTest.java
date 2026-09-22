package kr.co.cking.event.application;

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
import java.util.List;
import java.util.Optional;
import java.util.Set;

import kr.co.cking.event.application.dto.CachedEvent;
import kr.co.cking.event.application.dto.CachedEventPage;
import kr.co.cking.event.domain.DisplayStatus;
import kr.co.cking.event.domain.EventStatus;

import static org.assertj.core.api.Assertions.assertThat;

/** {@link EventCacheTest}와 같은 이유로 전용 prefix를 쓴다. */
class EventListCacheTest {

    private static final String TEST_KEY_PREFIX = "cache:event:list:test:";
    private static final String KEY_PATTERN = TEST_KEY_PREFIX + "*";

    private LettuceConnectionFactory connectionFactory;
    private RedisTemplate<String, CachedEventPage> redisTemplate;
    private EventListCache eventListCache;
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
        eventListCache = new EventListCache(redisTemplate, Clock.fixed(now, ZoneOffset.UTC), TEST_KEY_PREFIX);
    }

    @AfterEach
    void tearDown() {
        Set<String> keys = redisTemplate.keys(KEY_PATTERN);
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
        connectionFactory.destroy();
    }

    private CachedEvent event(long endAfterSeconds) {
        return event(endAfterSeconds, EventStatus.OPEN);
    }

    private CachedEvent event(long endAfterSeconds, EventStatus status) {
        return new CachedEvent(1L, 2L, "여름 이벤트", "설명",
                now, now.plusSeconds(endAfterSeconds), 3, "WEIGHTED", status);
    }

    @Test
    void 저장한_목록을_같은_조합_키로_다시_조회할_수_있다() {
        CachedEventPage page = new CachedEventPage(List.of(event(Duration.ofDays(5).toSeconds())), 1);

        eventListCache.save(1L, DisplayStatus.IN_PROGRESS, 0, 10, page);

        assertThat(eventListCache.find(1L, DisplayStatus.IN_PROGRESS, 0, 10)).contains(page);
    }

    @Test
    void 다른_조합_키는_서로_섞이지_않는다() {
        CachedEventPage page = new CachedEventPage(List.of(event(Duration.ofDays(5).toSeconds())), 1);
        eventListCache.save(1L, DisplayStatus.IN_PROGRESS, 0, 10, page);

        assertThat(eventListCache.find(1L, DisplayStatus.CLOSED, 0, 10)).isEmpty();
        assertThat(eventListCache.find(2L, DisplayStatus.IN_PROGRESS, 0, 10)).isEmpty();
        assertThat(eventListCache.find(1L, DisplayStatus.IN_PROGRESS, 1, 10)).isEmpty();
        assertThat(eventListCache.find(null, null, 0, 10)).isEmpty();
    }

    @Test
    void creatorId와_displayStatus가_없는_전체_조회도_캐싱된다() {
        CachedEventPage page = new CachedEventPage(List.of(event(Duration.ofDays(5).toSeconds())), 1);

        eventListCache.save(null, null, 0, 20, page);

        assertThat(eventListCache.find(null, null, 0, 20)).contains(page);
    }

    @Test
    void TTL은_5초를_넘지_않는다() {
        CachedEventPage page = new CachedEventPage(List.of(event(Duration.ofHours(3).toSeconds())), 1);

        eventListCache.save(1L, DisplayStatus.IN_PROGRESS, 0, 10, page);

        Long ttlSeconds = connectionFactory.getConnection().keyCommands()
                .ttl((TEST_KEY_PREFIX + "1:IN_PROGRESS:0:10").getBytes());
        assertThat(ttlSeconds).isLessThanOrEqualTo(5L).isGreaterThan(0L);
    }

    @Test
    void 페이지_안에_endAt이_5초보다_가까운_이벤트가_있으면_그때까지만_캐싱한다() {
        CachedEventPage page = new CachedEventPage(
                List.of(event(Duration.ofDays(5).toSeconds()), event(2)), 2);

        eventListCache.save(1L, DisplayStatus.IN_PROGRESS, 0, 10, page);

        Long ttlSeconds = connectionFactory.getConnection().keyCommands()
                .ttl((TEST_KEY_PREFIX + "1:IN_PROGRESS:0:10").getBytes());
        assertThat(ttlSeconds).isLessThanOrEqualTo(2L).isGreaterThan(0L);
    }

    /**
     * OPEN이고 endAt이 이미 지난 이벤트는 DisplayStatus.of()가 시간과 무관하게 이미
     * CLOSED로 굳혀서 더 이상 바뀌지 않으므로, staleness 위험이 없어 캐싱을 막을 이유가
     * 없다(리뷰에서 발견: 예전 구현은 이런 이벤트가 하나만 있어도 페이지 전체를 캐싱하지
     * 않아서 CLOSED 목록 조회가 사실상 캐싱되지 않았다).
     */
    @Test
    void OPEN이지만_endAt이_이미_지난_이벤트는_캐싱을_막지_않는다() {
        CachedEventPage page = new CachedEventPage(List.of(event(-1)), 1);

        eventListCache.save(1L, DisplayStatus.CLOSED, 0, 10, page);

        assertThat(eventListCache.find(1L, DisplayStatus.CLOSED, 0, 10)).contains(page);
    }

    /** CLOSING/CLOSED/DRAW_COMPLETED/PUBLISHED는 endAt과 무관하게 항상 CLOSED로 고정된다. */
    @Test
    void CLOSED_상태_이벤트는_endAt이_과거여도_캐싱을_막지_않는다() {
        CachedEventPage page = new CachedEventPage(List.of(event(-100, EventStatus.CLOSED)), 1);

        eventListCache.save(1L, DisplayStatus.CLOSED, 0, 10, page);

        assertThat(eventListCache.find(1L, DisplayStatus.CLOSED, 0, 10)).contains(page);
    }

    /** 마감된 이벤트가 섞여 있어도, 진짜 전환 임박한 OPEN 이벤트 기준으로만 TTL을 깎는다. */
    @Test
    void 마감된_이벤트와_임박한_OPEN_이벤트가_섞이면_OPEN_기준으로만_TTL을_깎는다() {
        CachedEventPage page = new CachedEventPage(
                List.of(event(-100, EventStatus.CLOSED), event(2)), 2);

        eventListCache.save(1L, DisplayStatus.CLOSED, 0, 10, page);

        Long ttlSeconds = connectionFactory.getConnection().keyCommands()
                .ttl((TEST_KEY_PREFIX + "1:CLOSED:0:10").getBytes());
        assertThat(ttlSeconds).isLessThanOrEqualTo(2L).isGreaterThan(0L);
    }

    @Test
    void 없는_조합은_빈값이다() {
        Optional<CachedEventPage> found = eventListCache.find(999L, DisplayStatus.UPCOMING, 0, 10);

        assertThat(found).isEmpty();
    }

    /** {@link EventCacheTest#역직렬화에_실패한_캐시는_miss로_처리한다}와 같은 이유·같은 방식. */
    @Test
    void 역직렬화에_실패한_캐시는_miss로_처리한다() {
        byte[] key = (TEST_KEY_PREFIX + "1:IN_PROGRESS:0:10").getBytes();
        connectionFactory.getConnection().stringCommands().set(key, "corrupted-not-a-java-object".getBytes());

        assertThat(eventListCache.find(1L, DisplayStatus.IN_PROGRESS, 0, 10)).isEmpty();
    }
}
