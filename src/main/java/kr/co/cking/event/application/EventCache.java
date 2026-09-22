package kr.co.cking.event.application;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.SerializationException;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.util.Optional;

import kr.co.cking.event.application.dto.CachedEvent;
import lombok.extern.slf4j.Slf4j;

/**
 * 취합v1.5.4 §13.3(NFR-02/FR-03 확정) 기준: TTL은 5초로 고정하고, 어떤 경우에도
 * endAt을 넘지 않는다. 이 캐시는 정확성을 책임지지 않는다 — 마감 임박 트래픽에 대한
 * 순수 DB 부하 완화용이며, 정확성은 상태 표시 규칙과 {@link #evict} 무효화가 담당한다.
 * Event 상태 변경 시(EventCommandService 등) 반드시 {@link #evict}를 호출해야 한다.
 *
 * <p>키 prefix는 {@code cache.event.prefix}로 설정 가능하다 — 테스트가 실제 운영
 * 캐시 네임스페이스를 건드리지 않고 자기 네임스페이스(예: {@code cache:event:test:})만
 * 정리할 수 있게 하기 위함이다.
 */
@Component
@Slf4j
public class EventCache {

    private static final Duration MAX_TTL = Duration.ofSeconds(5);

    private final RedisTemplate<String, CachedEvent> redisTemplate;
    private final Clock clock;
    private final String keyPrefix;

    public EventCache(RedisTemplate<String, CachedEvent> redisTemplate,
                       Clock clock,
                       @Value("${cache.event.prefix:cache:event:}") String keyPrefix) {
        this.redisTemplate = redisTemplate;
        this.clock = clock;
        this.keyPrefix = keyPrefix;
    }

    /**
     * CachedEvent에 컴포넌트를 추가하는 배포의 롤링 중첩 구간에는, 구버전 인스턴스가 쓴
     * payload를 신버전이 읽어 역직렬화가 실패할 수 있다(JDK 직렬화라 record 역직렬화가
     * 없는 컴포넌트를 null로 채운 뒤 압축 생성자를 그대로 호출하기 때문). 그 payload를
     * 기본값으로 복원해 반환하면 실제 값과 다른 데이터를 정상 응답처럼 내보내게 되므로,
     * 대신 cache miss로 처리한다 — 호출자가 DB에서 다시 읽어 같은 키를 새 payload로
     * 덮어쓰므로 다음 조회부터는 정상화된다.
     */
    public Optional<CachedEvent> find(Long eventId) {
        try {
            return Optional.ofNullable(redisTemplate.opsForValue().get(key(eventId)));
        } catch (SerializationException exception) {
            log.warn("Failed to deserialize cached event, treating as cache miss: eventId={}", eventId, exception);
            return Optional.empty();
        }
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
        return keyPrefix + eventId;
    }
}
