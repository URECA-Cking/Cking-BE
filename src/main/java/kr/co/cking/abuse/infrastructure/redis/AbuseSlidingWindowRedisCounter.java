package kr.co.cking.abuse.infrastructure.redis;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

import kr.co.cking.abuse.config.AbuseProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** Abuse Detection의 ZSET Sliding Window를 Lua로 원자 갱신하고 현재 count를 반환한다. */
@Component
public class AbuseSlidingWindowRedisCounter {

    private final StringRedisTemplate redisTemplate;
    private final DefaultRedisScript<Long> slidingWindowCountLuaScript;

    /** Redis 실행기와 Sliding Window 전용 Lua 스크립트를 주입받는다. */
    public AbuseSlidingWindowRedisCounter(
            StringRedisTemplate redisTemplate,
            @Qualifier("abuseSlidingWindowCountLuaScript") DefaultRedisScript<Long> slidingWindowCountLuaScript
    ) {
        this.redisTemplate = redisTemplate;
        this.slidingWindowCountLuaScript = slidingWindowCountLuaScript;
    }

    /**
     * member를 현재 시각 점수로 기록하고 window 밖 항목을 제거한 뒤 원자적으로 count를 반환한다.
     * 일반 window에는 observationId, rotation window에는 requestId를 member로 전달한다.
     */
    public long recordAndCount(String key, Instant observedAt, String member, Duration window) {
        validateArguments(key, observedAt, member, window);

        Long count = redisTemplate.execute(
                slidingWindowCountLuaScript,
                List.of(key),
                String.valueOf(observedAt.toEpochMilli()),
                member,
                String.valueOf(window.toMillis()),
                String.valueOf(ttlSeconds(window)));
        if (count == null) {
            throw new IllegalStateException("Sliding Window Lua가 count를 반환하지 않았습니다.");
        }
        return count;
    }

    /** window와 고정 padding을 합친 TTL을 EXPIRE가 받을 초 단위로 올림 계산한다. */
    private long ttlSeconds(Duration window) {
        Duration ttl = window.plus(AbuseProperties.TTL_PADDING);
        return ttl.getSeconds() + (ttl.getNano() == 0 ? 0 : 1);
    }

    /** Lua 호출 전에 Redis key, member, 시각, window의 필수·양수 조건을 검증한다. */
    private void validateArguments(String key, Instant observedAt, String member, Duration window) {
        if (!StringUtils.hasText(key)) {
            throw new IllegalArgumentException("key는 필수입니다.");
        }
        Objects.requireNonNull(observedAt, "observedAt은 필수입니다.");
        if (!StringUtils.hasText(member)) {
            throw new IllegalArgumentException("member는 필수입니다.");
        }
        Objects.requireNonNull(window, "window는 필수입니다.");
        if (window.isZero() || window.isNegative()) {
            throw new IllegalArgumentException("window는 양수여야 합니다.");
        }
    }
}
