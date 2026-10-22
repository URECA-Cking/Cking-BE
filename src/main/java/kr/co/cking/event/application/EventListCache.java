package kr.co.cking.event.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.SerializationException;
import org.springframework.stereotype.Component;

import kr.co.cking.event.application.dto.CachedEvent;
import kr.co.cking.event.application.dto.CachedEventPage;
import kr.co.cking.event.domain.DisplayStatus;
import kr.co.cking.event.domain.EventStatus;
import lombok.extern.slf4j.Slf4j;

/**
 * 목록 조회(FR-P2-003, CLAUDE.md §3·§9) 캐시. {@link EventCache}(상세)와 같은 계약을
 * 따른다 — TTL 5초, endAt을 넘기지 않음. 능동 무효화는 하지 않는다: 목록 캐시 키 하나가
 * 여러 Event를 걸치고 있어 상태 변경마다 영향받는 조합을 역산하는 비용이 크고, TTL 5초의
 * staleness는 이미 §9가 허용한 범위다("정상적인 지연을 오류로 판단하지 않는다", "캐시의
 * Event 상태를 응모 승인의 최종 판단 근거로 쓰지 않는다").
 *
 * <p>키는 요청 축(creatorId, displayStatus, page, size)을 그대로 조합한다. 조합 수 자체는
 * 크지만 TTL이 5초라 Redis에 쌓이는 키 수는 "5초 동안 실제로 들어온 요청의 다양성"으로만
 * 제한된다 — 마감 임박 트래픽처럼 같은 조합에 요청이 몰리는 상황에서 의미가 있다.
 */
@Component
@Slf4j
public class EventListCache {

    private static final Duration MAX_TTL = Duration.ofSeconds(5);

    private final RedisTemplate<String, CachedEventPage> redisTemplate;
    private final Clock clock;
    private final String keyPrefix;

    public EventListCache(RedisTemplate<String, CachedEventPage> eventListRedisTemplate,
                           Clock clock,
                           @Value("${cache.event-list.prefix:cache:event:list:}") String keyPrefix) {
        this.redisTemplate = eventListRedisTemplate;
        this.clock = clock;
        this.keyPrefix = keyPrefix;
    }

    /** {@link EventCache#find}와 같은 이유로, 역직렬화 실패는 기본값 복원이 아니라 cache miss로 처리한다. */
    public Optional<CachedEventPage> find(Long creatorId, DisplayStatus displayStatus, int page, int size) {
        String key = key(creatorId, displayStatus, page, size);
        try {
            return Optional.ofNullable(redisTemplate.opsForValue().get(key));
        } catch (SerializationException exception) {
            log.warn("Failed to deserialize cached event page, treating as cache miss: key={}", key, exception);
            return Optional.empty();
        }
    }

    /**
     * OPEN이고 endAt이 아직 남은 이벤트만 놓고, 그중 가장 임박한 것을 기준으로 TTL을
     * 5초 이하로 깎는다. 그 조건에 해당하는 이벤트가 없으면 그냥 5초로 캐싱한다.
     *
     * <p>CLOSING/CLOSED/DRAW_COMPLETED/PUBLISHED는 {@link DisplayStatus#of}에서 시간과
     * 무관하게 항상 CLOSED로 고정되고, endAt이 이미 지난 OPEN도 마찬가지로 이미 CLOSED로
     * 굳어 더 이상 바뀌지 않는다 — 이런 이벤트는 캐싱해도 staleness 위험이 없으므로 TTL
     * 상한 계산에서 제외한다(전부 이런 이벤트뿐인 페이지, 예: displayStatus=CLOSED 조회를
     * 캐싱 자체가 안 되는 상태로 방치하지 않기 위함).
     */
    public void save(Long creatorId, DisplayStatus displayStatus, int page, int size, CachedEventPage value) {
        Instant now = clock.instant();
        Duration ttl = MAX_TTL;
        for (CachedEvent event : value.events()) {
            if (event.status() != EventStatus.OPEN) {
                continue;
            }
            Duration untilEnd = Duration.between(now, event.endAt());
            if (untilEnd.isNegative() || untilEnd.isZero()) {
                continue;
            }
            if (untilEnd.compareTo(ttl) < 0) {
                ttl = untilEnd;
            }
        }
        redisTemplate.opsForValue().set(key(creatorId, displayStatus, page, size), value, ttl);
    }

    private String key(Long creatorId, DisplayStatus displayStatus, int page, int size) {
        return "%s%s:%s:%d:%d".formatted(keyPrefix,
                creatorId == null ? "ALL" : creatorId,
                displayStatus == null ? "ALL" : displayStatus.name(),
                page, size);
    }
}
