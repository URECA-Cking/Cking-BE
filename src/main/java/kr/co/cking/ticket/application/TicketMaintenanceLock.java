package kr.co.cking.ticket.application;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import kr.co.cking.ticket.application.config.TicketRedisKeys;
import kr.co.cking.event.application.config.EntryRedisKeys;
import lombok.RequiredArgsConstructor;

/**
 * 수동 보정이 {@code (creatorId, memberId)}의 Redis 잔액을 덮어쓰는 동안 SPEND·EARN Lua가 같은 잔액을
 * 바꾸지 못하게 하는 maintenance lock이다. Lua의 lock 확인은 SPEND·EARN 쪽 이슈(#172)에서 처리한다.
 *
 * <p>lease는 60초이고 연장하지 않는다. 검사가 길어져 lock이 만료되면 {@link #setBalanceIfHeld}가 덮어쓰기를
 * 거부하므로, 만료는 보정 실패로 끝날 뿐 잔액을 잘못 쓰지 않는다. 해제는 token이 같을 때만 지운다.
 */
@Component
@RequiredArgsConstructor
public class TicketMaintenanceLock {

    private static final Duration LEASE = Duration.ofSeconds(60);

    // ponytail: 한 줄짜리 스크립트 두 개라 파일 대신 인라인으로 둔다. 늘어나면 scripts/로 옮긴다.
    private static final DefaultRedisScript<Long> RELEASE = new DefaultRedisScript<>(
            "if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('DEL', KEYS[1]) end return 0",
            Long.class);
    private static final DefaultRedisScript<Long> SET_IF_HELD = new DefaultRedisScript<>(
            "if redis.call('GET', KEYS[1]) == ARGV[1] then redis.call('SET', KEYS[2], ARGV[2]) return 1 end return 0",
            Long.class);

    private final StringRedisTemplate redisTemplate;

    /** lock을 얻으면 token을, 이미 다른 보정이 잡고 있으면 null을 반환한다. */
    public String acquire(Long creatorId, Long memberId) {
        String token = UUID.randomUUID().toString();
        Boolean acquired = redisTemplate.opsForValue()
                .setIfAbsent(TicketRedisKeys.maintenanceLock(creatorId, memberId), token, LEASE);
        return Boolean.TRUE.equals(acquired) ? token : null;
    }

    public void release(Long creatorId, Long memberId, String token) {
        redisTemplate.execute(RELEASE, List.of(TicketRedisKeys.maintenanceLock(creatorId, memberId)), token);
    }

    /** token이 아직 lock의 소유자일 때만 Redis 잔액을 덮어쓴다. 소유하지 않으면 false. */
    public boolean setBalanceIfHeld(Long creatorId, Long memberId, String token, long balance) {
        Long written = redisTemplate.execute(
                SET_IF_HELD,
                List.of(TicketRedisKeys.maintenanceLock(creatorId, memberId), EntryRedisKeys.balance(creatorId, memberId)),
                token, String.valueOf(balance));
        return Long.valueOf(1L).equals(written);
    }
}
