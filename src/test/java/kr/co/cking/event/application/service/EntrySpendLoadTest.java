package kr.co.cking.event.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import kr.co.cking.event.application.config.EntryRedisKeys;
import kr.co.cking.event.application.dto.EntrySpendResult;
import kr.co.cking.event.application.dto.enums.EntrySpendResultCode;
import kr.co.cking.ticket.application.config.TicketRedisKeys;

/**
 * NFR-06/FR-P2-043(취합v1.5.4 §15.4): 응모 Lua 동시성 정합성을 실측 부하로 검증한다. 서로 다른
 * 사용자가 정확히 1장씩 동시에 응모하는 시나리오에서 TPS·p95·p99·오류율을 재고, §15.1 불변식
 * (Redis Balance >= 0, requestId당 SPEND 1회, Entry·Ledger 중복 없음)이 깨지지 않는지 확인한다.
 *
 * <p>{@code test}에는 포함하지 않는다({@code build.gradle}의 {@code excludeTags 'load'}) — 매 커밋마다
 * 돌릴 상관관계 테스트가 아니라 필요할 때 {@code ./gradlew loadTest}로 실행하는 성능 확인용이다.
 * 로컬 MySQL·Redis가 떠 있어야 하고, SPEND Consumer(concurrency=1 고정, 취합v1.5.4 §13.1)가
 * DB 반영을 순차 처리하므로 동시 요청 수를 늘려도 DB 드레인 자체는 직렬화된다.
 */
@Tag("load")
@SpringBootTest
class EntrySpendLoadTest {

    private static final Logger log = LoggerFactory.getLogger(EntrySpendLoadTest.class);

    private static final int USER_COUNT = 300;
    private static final long OWNER_MEMBER_ID = 99_001L;
    private static final long CREATOR_ID = 99_101L;
    private static final long EVENT_ID = 99_201L;
    private static final long USER_ID_START = 99_301L;
    private static final long INITIAL_BALANCE = 5L;

    @Autowired
    private EntrySpendService entrySpendService;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM ticket_ledger WHERE creator_id = ?", CREATOR_ID);
        jdbcTemplate.update("DELETE FROM event_entry WHERE event_id = ?", EVENT_ID);
        jdbcTemplate.update("DELETE FROM user_ticket_balance WHERE creator_id = ?", CREATOR_ID);
        jdbcTemplate.update("DELETE FROM event WHERE event_id = ?", EVENT_ID);
        jdbcTemplate.update("DELETE FROM creator WHERE creator_id = ?", CREATOR_ID);
        // 로컬 공유 MySQL에는 다른 세션이 남긴 대량의 member 행이 있다(최대 id 백만 단위) -
        // member_id >= USER_ID_START처럼 위쪽이 열린 범위로 지우면 이 테스트와 무관한 행까지
        // 걸려 FK 위반이 난다. 이 테스트가 실제로 만든 좁은 구간만 지운다.
        jdbcTemplate.update("DELETE FROM member WHERE member_id = ?", OWNER_MEMBER_ID);
        jdbcTemplate.update("DELETE FROM member WHERE member_id BETWEEN ? AND ?",
                USER_ID_START, USER_ID_START + USER_COUNT - 1);

        List<String> keys = new ArrayList<>(List.of(
                EntryRedisKeys.status(EVENT_ID), EntryRedisKeys.endAt(EVENT_ID)));
        for (long userId = USER_ID_START; userId < USER_ID_START + USER_COUNT; userId++) {
            keys.add(TicketRedisKeys.balance(CREATOR_ID, userId));
        }
        redisTemplate.delete(keys);
    }

    @Test
    void 서로_다른_사용자_동시_응모에서_잔액_음수와_중복_반영이_없다() throws InterruptedException {
        seed();

        List<Long> userIds = IntStream.range(0, USER_COUNT)
                .mapToObj(i -> USER_ID_START + i)
                .toList();
        List<String> requestIds = userIds.stream().map(id -> UUID.randomUUID().toString()).toList();
        long[] latenciesMillis = new long[USER_COUNT];
        AtomicInteger successCount = new AtomicInteger();

        Instant start = Instant.now();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < USER_COUNT; i++) {
                int index = i;
                futures.add(executor.submit(() -> {
                    long callStart = System.nanoTime();
                    EntrySpendResult result = entrySpendService.spend(
                            EVENT_ID, userIds.get(index), CREATOR_ID, requestIds.get(index), 1);
                    latenciesMillis[index] = (System.nanoTime() - callStart) / 1_000_000;
                    if (result.code() == EntrySpendResultCode.SUCCESS) {
                        successCount.incrementAndGet();
                    } else {
                        log.warn("예상치 못한 결과코드: userId={}, code={}", userIds.get(index), result.code());
                    }
                }));
            }
            for (var future : futures) {
                future.get();
            }
        } catch (java.util.concurrent.ExecutionException e) {
            throw new AssertionError("동시 응모 호출 실패", e);
        }
        Duration elapsed = Duration.between(start, Instant.now());

        awaitDrain();
        report(elapsed, latenciesMillis, successCount.get());

        assertThat(successCount.get()).isEqualTo(USER_COUNT);
        assertNoNegativeBalance();
        assertNoDuplicateEntry();
        assertNoDuplicateSpendLedger();
    }

    private void seed() {
        jdbcTemplate.update("INSERT INTO member (member_id, name, role) VALUES (?, ?, ?)",
                OWNER_MEMBER_ID, "부하테스트 크리에이터 회원", "USER");
        jdbcTemplate.update("INSERT INTO creator (creator_id, member_id, name) VALUES (?, ?, ?)",
                CREATOR_ID, OWNER_MEMBER_ID, "부하테스트 크리에이터");
        jdbcTemplate.update("""
                INSERT INTO event (event_id, creator_id, title, start_at, end_at, winner_count, draw_method,
                                    status, request_id, created_by)
                VALUES (?, ?, ?, '2026-09-01 00:00:00', '2026-12-01 00:00:00', 1, 'WEIGHTED', 'OPEN', ?, ?)
                """, EVENT_ID, CREATOR_ID, "부하테스트 이벤트", UUID.randomUUID().toString(), OWNER_MEMBER_ID);

        redisTemplate.opsForValue().set(EntryRedisKeys.status(EVENT_ID), "OPEN");
        redisTemplate.opsForValue().set(EntryRedisKeys.endAt(EVENT_ID), String.valueOf(9_999_999_999_999L));

        for (long userId = USER_ID_START; userId < USER_ID_START + USER_COUNT; userId++) {
            jdbcTemplate.update("INSERT INTO member (member_id, name, role) VALUES (?, ?, ?)",
                    userId, "부하테스트 응모자 " + userId, "USER");
            jdbcTemplate.update("""
                    INSERT INTO user_ticket_balance (member_id, creator_id, balance, updated_at)
                    VALUES (?, ?, ?, NOW())
                    """, userId, CREATOR_ID, INITIAL_BALANCE);
            redisTemplate.opsForValue().set(TicketRedisKeys.balance(CREATOR_ID, userId), String.valueOf(INITIAL_BALANCE));
        }
    }

    /** SPEND Consumer(concurrency=1)가 event_entry 반영을 끝낼 때까지 최대 30초 기다린다. */
    private void awaitDrain() throws InterruptedException {
        Instant deadline = Instant.now().plusSeconds(30);
        while (Instant.now().isBefore(deadline)) {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM event_entry WHERE event_id = ?", Integer.class, EVENT_ID);
            if (count != null && count >= USER_COUNT) {
                return;
            }
            TimeUnit.MILLISECONDS.sleep(200);
        }
        throw new AssertionError("SPEND Consumer가 30초 안에 모든 Entry를 반영하지 못했습니다.");
    }

    private void assertNoNegativeBalance() {
        Integer negativeCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM user_ticket_balance WHERE creator_id = ? AND balance < 0",
                Integer.class, CREATOR_ID);
        assertThat(negativeCount).isZero();
    }

    private void assertNoDuplicateEntry() {
        Integer total = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM event_entry WHERE event_id = ?", Integer.class, EVENT_ID);
        Integer distinct = jdbcTemplate.queryForObject(
                "SELECT COUNT(DISTINCT request_id) FROM event_entry WHERE event_id = ?", Integer.class, EVENT_ID);
        assertThat(total).isEqualTo(USER_COUNT);
        assertThat(distinct).isEqualTo(total);
    }

    private void assertNoDuplicateSpendLedger() {
        Integer total = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ticket_ledger WHERE creator_id = ? AND type = 'SPEND'",
                Integer.class, CREATOR_ID);
        Integer distinct = jdbcTemplate.queryForObject(
                "SELECT COUNT(DISTINCT request_id) FROM ticket_ledger WHERE creator_id = ? AND type = 'SPEND'",
                Integer.class, CREATOR_ID);
        assertThat(total).isEqualTo(USER_COUNT);
        assertThat(distinct).isEqualTo(total);
    }

    private void report(Duration elapsed, long[] latenciesMillis, int successCount) {
        long[] sorted = latenciesMillis.clone();
        java.util.Arrays.sort(sorted);
        double tps = USER_COUNT / Math.max(elapsed.toMillis() / 1000.0, 0.001);
        log.info("""
                        [NFR-06 부하 테스트 결과] 동시 요청 수={}, 성공={}, 오류율={}%
                        elapsed={}ms, TPS={}
                        p50={}ms, p95={}ms, p99={}ms, max={}ms""",
                USER_COUNT, successCount, String.format("%.2f", (USER_COUNT - successCount) * 100.0 / USER_COUNT),
                elapsed.toMillis(), String.format("%.1f", tps),
                percentile(sorted, 0.50), percentile(sorted, 0.95), percentile(sorted, 0.99),
                sorted[sorted.length - 1]);
    }

    private long percentile(long[] sorted, double p) {
        int index = (int) Math.ceil(p * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(index, sorted.length - 1))];
    }
}
