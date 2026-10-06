package kr.co.cking.abuse.infrastructure.redis;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import kr.co.cking.abuse.application.model.CooldownLease;
import kr.co.cking.abuse.application.port.AbuseCooldownStore;
import kr.co.cking.abuse.domain.AbuseScopeHash;
import kr.co.cking.abuse.domain.AbuseType;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/** Redis의 UUID 소유 Token으로 Detection Cooldown을 원자적으로 관리하는 Adapter다. */
@Component
public class RedisAbuseCooldownStore implements AbuseCooldownStore {

    private final StringRedisTemplate redisTemplate;
    private final DefaultRedisScript<Long> acquireLuaScript;
    private final DefaultRedisScript<Long> releaseLuaScript;

    /** Redis 실행기와 Cooldown 획득·해제 Lua 스크립트를 주입받는다. */
    public RedisAbuseCooldownStore(
            StringRedisTemplate redisTemplate,
            @Qualifier("abuseCooldownAcquireLuaScript") DefaultRedisScript<Long> acquireLuaScript,
            @Qualifier("abuseCooldownReleaseLuaScript") DefaultRedisScript<Long> releaseLuaScript
    ) {
        this.redisTemplate = Objects.requireNonNull(redisTemplate, "redisTemplate은 필수입니다.");
        this.acquireLuaScript = Objects.requireNonNull(acquireLuaScript, "acquireLuaScript는 필수입니다.");
        this.releaseLuaScript = Objects.requireNonNull(releaseLuaScript, "releaseLuaScript는 필수입니다.");
    }

    /** 같은 탐지 유형과 scope에 SET NX EX로 UUID Token을 선점하고 성공한 Lease만 반환한다. */
    @Override
    public Optional<CooldownLease> tryAcquire(AbuseType abuseType, String scopeHash, Duration ttl) {
        Objects.requireNonNull(abuseType, "abuseType은 필수입니다.");
        AbuseScopeHash.requireValidHash(scopeHash, "scopeHash");
        long ttlSeconds = ttlSeconds(ttl);
        CooldownLease lease = new CooldownLease(abuseType, scopeHash, UUID.randomUUID());

        Long acquired = redisTemplate.execute(
                acquireLuaScript,
                List.of(AbuseRedisKeys.cooldown(abuseType, scopeHash)),
                lease.token().toString(),
                String.valueOf(ttlSeconds));
        return successOrFailure(acquired, "획득") ? Optional.of(lease) : Optional.empty();
    }

    /** Lease의 UUID Token이 현재 Redis 값과 같을 때만 Cooldown key를 삭제한다. */
    @Override
    public boolean release(CooldownLease lease) {
        Objects.requireNonNull(lease, "lease는 필수입니다.");
        Long released = redisTemplate.execute(
                releaseLuaScript,
                List.of(AbuseRedisKeys.cooldown(lease.abuseType(), lease.scopeHash())),
                lease.token().toString());
        return successOrFailure(released, "해제");
    }

    /** Lua의 0·1 응답만 각각 점유 실패 또는 성공으로 해석하고 나머지는 Redis 이상으로 전파한다. */
    private boolean successOrFailure(Long result, String operation) {
        if (result == null || (result != 0L && result != 1L)) {
            throw new IllegalStateException("Cooldown " + operation + " Lua가 유효하지 않은 결과를 반환했습니다.");
        }
        return result == 1L;
    }

    /** SET EX가 요구하는 양의 정수 초 TTL로 변환하고 소수 초 입력은 거부한다. */
    private long ttlSeconds(Duration ttl) {
        Objects.requireNonNull(ttl, "ttl은 필수입니다.");
        if (ttl.isZero() || ttl.isNegative() || ttl.getNano() != 0) {
            throw new IllegalArgumentException("Cooldown TTL은 양의 정수 초여야 합니다.");
        }
        return ttl.getSeconds();
    }
}
