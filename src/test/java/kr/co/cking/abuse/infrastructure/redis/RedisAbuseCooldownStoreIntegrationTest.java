package kr.co.cking.abuse.infrastructure.redis;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import kr.co.cking.abuse.application.model.CooldownLease;
import kr.co.cking.abuse.application.port.AbuseCooldownStore;
import kr.co.cking.abuse.domain.AbuseScopeHash;
import kr.co.cking.abuse.domain.AbuseType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

/** Redis Cooldown의 원자 점유, Token 소유권 해제와 만료 후 재획득을 통합 검증한다. */
@SpringBootTest
class RedisAbuseCooldownStoreIntegrationTest {

    private static final String KEY_PREFIX = "abuse:v1:cooldown:";
    private static final AbuseType ABUSE_TYPE = AbuseType.MISSION_REQUEST_BURST;
    private static final Duration TTL = Duration.ofSeconds(2);

    @Autowired
    private AbuseCooldownStore cooldownStore;

    @Autowired
    private StringRedisTemplate redisTemplate;

    /** 테스트가 만든 Cooldown key만 삭제해 다음 테스트 실행과 격리한다. */
    @AfterEach
    void cleanUp() {
        Set<String> keys = redisTemplate.keys(KEY_PREFIX + "*");
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
    }

    /** 같은 Scope와 AbuseType으로 동시에 요청해도 정확히 하나만 Lease를 획득한다. */
    @Test
    void 동시_Cooldown_획득에서_최초_한_요청만_Lease를_획득한다() throws Exception {
        String scopeHash = scopeHash("concurrent");
        int requestCount = 32;
        CountDownLatch ready = new CountDownLatch(requestCount);
        CountDownLatch start = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(requestCount)) {
            List<Future<Optional<CooldownLease>>> results = IntStream.range(0, requestCount)
                    .mapToObj(index -> executor.submit(() -> acquireAfterStart(ready, start, scopeHash)))
                    .toList();

            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            long acquiredCount = results.stream().map(this::awaitLease).filter(Optional::isPresent).count();
            assertThat(acquiredCount).isEqualTo(1L);
        }
    }

    /** 다른 요청의 UUID Token으로 만든 Lease는 현재 Cooldown을 해제하지 못한다. */
    @Test
    void 다른_Token으로는_Cooldown을_해제할_수_없다() {
        String scopeHash = scopeHash("token-mismatch");
        CooldownLease owner = cooldownStore.tryAcquire(ABUSE_TYPE, scopeHash, TTL).orElseThrow();
        CooldownLease stranger = new CooldownLease(ABUSE_TYPE, scopeHash, java.util.UUID.randomUUID());

        assertThat(cooldownStore.release(stranger)).isFalse();
        assertThat(cooldownStore.tryAcquire(ABUSE_TYPE, scopeHash, TTL)).isEmpty();
        assertThat(cooldownStore.release(owner)).isTrue();
    }

    /** Redis TTL이 만료되면 같은 Scope와 AbuseType도 새 Lease로 다시 획득할 수 있다. */
    @Test
    void TTL_만료_후에는_Cooldown을_다시_획득한다() throws InterruptedException {
        String scopeHash = scopeHash("expiration");
        CooldownLease first = cooldownStore.tryAcquire(ABUSE_TYPE, scopeHash, Duration.ofSeconds(1)).orElseThrow();

        assertThat(redisTemplate.opsForValue().get(AbuseRedisKeys.cooldown(ABUSE_TYPE, scopeHash)))
                .isEqualTo(first.token().toString());
        Thread.sleep(1_100L);

        Optional<CooldownLease> reacquired = cooldownStore.tryAcquire(ABUSE_TYPE, scopeHash, TTL);
        assertThat(reacquired).isPresent();
        assertThat(reacquired.orElseThrow().token()).isNotEqualTo(first.token());
    }

    /** 동시 시작 신호 뒤 같은 Cooldown key 획득을 시도한다. */
    private Optional<CooldownLease> acquireAfterStart(
            CountDownLatch ready, CountDownLatch start, String scopeHash
    ) throws InterruptedException {
        ready.countDown();
        if (!start.await(5, TimeUnit.SECONDS)) {
            throw new AssertionError("동시 Cooldown 시작 신호를 받지 못했습니다.");
        }
        return cooldownStore.tryAcquire(ABUSE_TYPE, scopeHash, TTL);
    }

    /** Future의 Lease 결과를 테스트 실패 원인을 보존해 반환한다. */
    private Optional<CooldownLease> awaitLease(Future<Optional<CooldownLease>> future) {
        try {
            return future.get(10, TimeUnit.SECONDS);
        } catch (Exception exception) {
            throw new AssertionError("동시 Cooldown 획득에 실패했습니다.", exception);
        }
    }

    /** 테스트별 독립 Redis key를 만들기 위해 canonical scope를 hash로 변환한다. */
    private String scopeHash(String suffix) {
        return AbuseScopeHash.fromCanonicalValue("USER:COOLDOWN-TEST:" + suffix);
    }
}
