package kr.co.cking.abuse.infrastructure.redis;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

/** Sliding Window Lua의 ZSET 갱신, TTL, distinct count와 동시성 계약을 통합 검증한다. */
@SpringBootTest
class AbuseSlidingWindowRedisCounterIntegrationTest {

    private static final String KEY_PREFIX = "abuse:v1:test:sliding-window:";

    @Autowired
    private AbuseSlidingWindowRedisCounter counter;

    @Autowired
    private StringRedisTemplate redisTemplate;

    /** 테스트가 생성한 Sliding Window key만 삭제해 다음 실행과 격리한다. */
    @AfterEach
    void cleanUp() {
        Set<String> keys = redisTemplate.keys(KEY_PREFIX + "*");
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
    }

    /** Redis 실행 시각보다 충분히 오래된 score를 제거하고 현재 member count와 TTL을 갱신한다. */
    @Test
    void window_밖_데이터를_제거하고_count와_TTL을_갱신한다() {
        String key = key("expiration");
        Duration window = Duration.ofSeconds(10);
        redisTemplate.opsForZSet().add(key, "expired-observation", 0);

        long count = counter.recordAndCount(key, "current-observation", window);

        assertThat(count).isEqualTo(1L);
        assertThat(redisTemplate.opsForZSet().range(key, 0, -1))
                .containsExactly("current-observation");
        assertThat(redisTemplate.getExpire(key, TimeUnit.SECONDS)).isBetween(69L, 70L);
    }

    /** 같은 requestId를 rotation member로 재사용하면 ZSET distinct count가 증가하지 않는다. */
    @Test
    void 같은_requestId_replay는_distinct_count를_증가시키지_않는다() {
        String key = key("rotation");
        Duration window = Duration.ofMinutes(1);

        long first = counter.recordAndCount(key, "request-1", window);
        long replay = counter.recordAndCount(key, "request-1", window);
        long next = counter.recordAndCount(key, "request-2", window);

        assertThat(first).isEqualTo(1L);
        assertThat(replay).isEqualTo(1L);
        assertThat(next).isEqualTo(2L);
    }

    /** 업무 observedAt의 순서와 무관하게 Redis Lua의 실제 실행 순서로 count를 누적한다. */
    @Test
    void observedAt_순서가_역전돼도_Redis_실행_순서로_count를_반환한다() {
        String key = key("execution-order");
        Duration window = Duration.ofMinutes(1);
        String laterObservedMember = "observation-observed-at-20s";
        String earlierObservedMember = "observation-observed-at-10s";

        long laterExecutedFirst = counter.recordAndCount(key, laterObservedMember, window);
        long earlierExecutedLater = counter.recordAndCount(key, earlierObservedMember, window);

        assertThat(laterExecutedFirst).isEqualTo(1L);
        assertThat(earlierExecutedLater).isEqualTo(2L);
        assertThat(redisTemplate.opsForZSet().zCard(key)).isEqualTo(2L);
    }

    /** 동시 요청에서도 각 Lua 실행 직후의 count와 최종 ZSET count가 원자적으로 일치한다. */
    @Test
    void 동시_요청에서_원자적으로_Sliding_Window을_갱신하고_count를_반환한다() throws Exception {
        String key = key("concurrent");
        Duration window = Duration.ofMinutes(1);
        int requestCount = 32;
        CountDownLatch ready = new CountDownLatch(requestCount);
        CountDownLatch start = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(requestCount)) {
            List<Future<Long>> results = IntStream.range(0, requestCount)
                    .mapToObj(index -> executor.submit(() -> recordAfterStart(ready, start, key, window, index)))
                    .toList();

            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<Long> counts = results.stream().map(this::awaitCount).sorted().toList();
            assertThat(counts).containsExactlyElementsOf(
                    IntStream.rangeClosed(1, requestCount).mapToObj(Long::valueOf).toList());
            assertThat(redisTemplate.opsForZSet().zCard(key)).isEqualTo((long) requestCount);
        }
    }

    /** 시작 신호 뒤 고유 observationId를 member로 Lua count 호출을 실행한다. */
    private long recordAfterStart(
            CountDownLatch ready,
            CountDownLatch start,
            String key,
            Duration window,
            int index
    ) throws InterruptedException {
        ready.countDown();
        if (!start.await(5, TimeUnit.SECONDS)) {
            throw new AssertionError("동시 요청 시작 신호를 받지 못했습니다.");
        }
        return counter.recordAndCount(key, "observation-" + index, window);
    }

    /** Future 결과를 테스트 실패 원인을 보존한 long count로 반환한다. */
    private long awaitCount(Future<Long> future) {
        try {
            return future.get(10, TimeUnit.SECONDS);
        } catch (Exception exception) {
            throw new AssertionError("동시 Sliding Window Lua 실행에 실패했습니다.", exception);
        }
    }

    /** 테스트 간 충돌하지 않는 Abuse 전용 Redis key를 생성한다. */
    private String key(String suffix) {
        return KEY_PREFIX + suffix;
    }
}
