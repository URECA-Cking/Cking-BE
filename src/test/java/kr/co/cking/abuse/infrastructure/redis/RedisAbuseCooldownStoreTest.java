package kr.co.cking.abuse.infrastructure.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import kr.co.cking.abuse.application.model.CooldownLease;
import kr.co.cking.abuse.domain.AbuseScopeHash;
import kr.co.cking.abuse.domain.AbuseType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

/** RedisAbuseCooldownStore가 Lua 호출과 Lease 소유권 계약을 지키는지 단위 검증한다. */
class RedisAbuseCooldownStoreTest {

    private static final AbuseType ABUSE_TYPE = AbuseType.MISSION_REQUEST_BURST;
    private static final String SCOPE_HASH = AbuseScopeHash.fromCanonicalValue("USER:123");
    private static final Duration TTL = Duration.ofSeconds(20);

    private StringRedisTemplate redisTemplate;
    private DefaultRedisScript<Long> acquireLuaScript;
    private DefaultRedisScript<Long> releaseLuaScript;
    private RedisAbuseCooldownStore cooldownStore;

    /** 각 테스트가 독립된 RedisTemplate mock과 Lua Script를 사용하도록 초기화한다. */
    @BeforeEach
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        acquireLuaScript = new DefaultRedisScript<>();
        releaseLuaScript = new DefaultRedisScript<>();
        cooldownStore = new RedisAbuseCooldownStore(redisTemplate, acquireLuaScript, releaseLuaScript);
    }

    /** SET NX EX 선점 성공이면 Redis에 전달한 UUID와 같은 소유 Token Lease를 반환한다. */
    @Test
    void 점유에_성공하면_UUID_Token_Lease를_반환한다() {
        when(redisTemplate.execute(eq(acquireLuaScript), eq(List.of(key())), any(), eq("20")))
                .thenReturn(1L);

        Optional<CooldownLease> result = cooldownStore.tryAcquire(ABUSE_TYPE, SCOPE_HASH, TTL);

        assertThat(result).isPresent();
        ArgumentCaptor<Object> token = ArgumentCaptor.forClass(Object.class);
        verify(redisTemplate).execute(eq(acquireLuaScript), eq(List.of(key())), token.capture(), eq("20"));
        assertThat(token.getValue()).isEqualTo(result.orElseThrow().token().toString());
    }

    /** 이미 같은 scope와 유형이 점유 중이면 신규 Lease를 만들지 않는다. */
    @Test
    void 이미_점유_중이면_빈_Lease를_반환한다() {
        when(redisTemplate.execute(eq(acquireLuaScript), eq(List.of(key())), any(), eq("20")))
                .thenReturn(0L);

        Optional<CooldownLease> result = cooldownStore.tryAcquire(ABUSE_TYPE, SCOPE_HASH, TTL);

        assertThat(result).isEmpty();
    }

    /** 저장 Token이 같은 Lease만 release Lua에서 삭제 성공으로 해석한다. */
    @Test
    void 일치하는_Token으로_해제하면_true를_반환한다() {
        CooldownLease lease = new CooldownLease(ABUSE_TYPE, SCOPE_HASH, java.util.UUID.randomUUID());
        when(redisTemplate.execute(eq(releaseLuaScript), eq(List.of(key())), eq(lease.token().toString())))
                .thenReturn(1L);

        boolean released = cooldownStore.release(lease);

        assertThat(released).isTrue();
    }

    /** release Lua가 Token 불일치로 삭제하지 않으면 false를 반환한다. */
    @Test
    void 다른_Token이면_해제에_실패한다() {
        CooldownLease lease = new CooldownLease(ABUSE_TYPE, SCOPE_HASH, java.util.UUID.randomUUID());
        when(redisTemplate.execute(eq(releaseLuaScript), eq(List.of(key())), eq(lease.token().toString())))
                .thenReturn(0L);

        boolean released = cooldownStore.release(lease);

        assertThat(released).isFalse();
    }

    /** Lua가 null을 반환하면 점유 실패로 숨기지 않고 Redis 이상으로 전파한다. */
    @Test
    void Lua가_null을_반환하면_정상_점유_실패로_숨기지_않는다() {
        when(redisTemplate.execute(eq(acquireLuaScript), eq(List.of(key())), any(), eq("20")))
                .thenReturn((Long) null);

        assertThatThrownBy(() -> cooldownStore.tryAcquire(ABUSE_TYPE, SCOPE_HASH, TTL))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Cooldown 획득 Lua가 유효하지 않은 결과를 반환했습니다.");
    }

    /** Lua가 0·1 이외 값을 반환하면 Token 불일치로 숨기지 않고 Redis 이상으로 전파한다. */
    @Test
    void Lua가_예상_밖_값을_반환하면_정상_해제_실패로_숨기지_않는다() {
        CooldownLease lease = new CooldownLease(ABUSE_TYPE, SCOPE_HASH, java.util.UUID.randomUUID());
        when(redisTemplate.execute(eq(releaseLuaScript), eq(List.of(key())), eq(lease.token().toString())))
                .thenReturn(2L);

        assertThatThrownBy(() -> cooldownStore.release(lease))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Cooldown 해제 Lua가 유효하지 않은 결과를 반환했습니다.");
    }

    /** Cooldown 획득 중 Redis 실행 오류가 나면 점유 실패로 바꾸지 않고 원래 오류를 전파한다. */
    @Test
    void Cooldown_획득_오류를_점유_실패로_숨기지_않는다() {
        RuntimeException failure = new RuntimeException("Cooldown 획득 오류");
        when(redisTemplate.execute(eq(acquireLuaScript), eq(List.of(key())), any(), eq("20")))
                .thenThrow(failure);

        assertThatThrownBy(() -> cooldownStore.tryAcquire(ABUSE_TYPE, SCOPE_HASH, TTL))
                .isSameAs(failure);
    }

    /** Cooldown 해제 중 Redis 실행 오류가 나면 Token 불일치로 바꾸지 않고 원래 오류를 전파한다. */
    @Test
    void Cooldown_해제_오류를_Token_불일치로_숨기지_않는다() {
        CooldownLease lease = new CooldownLease(ABUSE_TYPE, SCOPE_HASH, java.util.UUID.randomUUID());
        RuntimeException failure = new RuntimeException("Cooldown 해제 오류");
        when(redisTemplate.execute(eq(releaseLuaScript), eq(List.of(key())), eq(lease.token().toString())))
                .thenThrow(failure);

        assertThatThrownBy(() -> cooldownStore.release(lease))
                .isSameAs(failure);
    }

    /** SET EX에 전달할 수 없는 0·음수·소수 초 TTL은 Redis 호출 전에 거부한다. */
    @Test
    void 양의_정수_초가_아닌_TTL을_거부한다() {
        assertThatThrownBy(() -> cooldownStore.tryAcquire(ABUSE_TYPE, SCOPE_HASH, null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> cooldownStore.tryAcquire(ABUSE_TYPE, SCOPE_HASH, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> cooldownStore.tryAcquire(ABUSE_TYPE, SCOPE_HASH, Duration.ofMillis(500)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** 테스트 대상의 canonical key를 실제 Adapter와 같은 규칙으로 생성한다. */
    private String key() {
        return AbuseRedisKeys.cooldown(ABUSE_TYPE, SCOPE_HASH);
    }
}
