package kr.co.cking.event.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

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

import kr.co.cking.creator.domain.Creator;
import kr.co.cking.creator.repository.CreatorRepository;
import kr.co.cking.event.application.config.EntryRedisKeys;
import kr.co.cking.event.application.dto.EntrySpendResult;
import kr.co.cking.event.application.dto.enums.EntrySpendResultCode;
import kr.co.cking.event.domain.Event;
import kr.co.cking.event.domain.EventStatus;
import kr.co.cking.event.repository.EventRepository;
import kr.co.cking.member.domain.Member;
import kr.co.cking.member.domain.MemberRole;
import kr.co.cking.member.repository.MemberRepository;
import kr.co.cking.ticket.application.config.TicketRedisKeys;
import kr.co.cking.ticket.domain.UserTicketBalance;
import kr.co.cking.ticket.repository.UserTicketBalanceRepository;

/**
 * NFR-06/FR-P2-043(취합v1.5.4 §15.4) 실측 부하 테스트. 두 시나리오를 나눠 잰다.
 *
 * <ol>
 *   <li><b>처리량</b>: 서로 다른 사용자 {@value #USER_COUNT}명이 각자 자기 Balance에 응모한다. 요청끼리
 *       같은 Redis 키·DB 행을 다투지 않으므로 Lua 원자성이 시험받는 구간이 아니라 순수 처리량
 *       (TPS·p50/p95/p99·오류율) 측정이다.</li>
 *   <li><b>경합</b>: 한 사용자가 보유한 것보다 많은 요청을 동시에 던져 같은 Balance 키를 다투게 한다.
 *       취합v1.5.4 §14 시나리오 5·6(보유량 초과 동시 응모에도 Redis Balance 음수 0건, 요청 수량만큼
 *       정확히 차감)에 해당하며, 여기서 Lua 원자성이 실제로 시험된다.</li>
 * </ol>
 *
 * <p>{@code test}에는 포함하지 않는다({@code build.gradle}의 {@code excludeTags 'load'}) — 필요할 때
 * {@code ./gradlew loadTest}로 실행한다. 로컬 MySQL·Redis가 떠 있어야 하고, SPEND Consumer는
 * concurrency=1 고정(취합v1.5.4 §13.1)이라 DB 반영은 직렬로 처리된다.
 *
 * <p>식별자는 하드코딩하지 않고 auto-increment가 배정한 값을 쓴다 — 로컬 공유 DB의 카운터가
 * 어디까지 올라가 있든 충돌하지 않고, 정리도 이 테스트가 만든 ID로만 한다.
 */
@Tag("load")
@SpringBootTest
class EntrySpendLoadTest {

    private static final Logger log = LoggerFactory.getLogger(EntrySpendLoadTest.class);

    /** 처리량 시나리오의 동시 요청 수(= 사용자 수, 1인 1건). */
    private static final int USER_COUNT = 300;
    /** 경합 시나리오의 동시 요청 수. 보유 잔액보다 많아야 초과분이 INSUFFICIENT_BALANCE로 걸린다. */
    private static final int CONTENTION_REQUESTS = 300;
    /** 경합 시나리오에서 한 사용자에게 주는 잔액. 이 수만큼만 SUCCESS여야 한다. */
    private static final long CONTENTION_BALANCE = 150L;
    private static final long THROUGHPUT_BALANCE = 5L;
    private static final long FAR_FUTURE_MILLIS = 9_999_999_999_999L;

    @Autowired
    private EntrySpendService entrySpendService;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private CreatorRepository creatorRepository;

    @Autowired
    private EventRepository eventRepository;

    @Autowired
    private UserTicketBalanceRepository userTicketBalanceRepository;

    private Long ownerMemberId;
    private Long creatorId;
    private Long eventId;
    private List<Long> throughputUserIds;
    private Long contentionUserId;

    @BeforeEach
    void setUp() {
        Member owner = memberRepository.saveAndFlush(new Member("부하테스트 크리에이터 회원", null, null, MemberRole.USER));
        ownerMemberId = owner.getMemberId();
        Creator creator = creatorRepository.saveAndFlush(new Creator(ownerMemberId, "부하테스트 크리에이터"));
        creatorId = creator.getCreatorId();

        Event event = eventRepository.saveAndFlush(Event.builder()
                .creatorId(creatorId)
                .requestId(UUID.randomUUID().toString())
                .title("부하테스트 이벤트")
                .startAt(Instant.parse("2026-09-01T00:00:00Z"))
                .endAt(Instant.parse("2026-12-01T00:00:00Z"))
                .winnerCount(1)
                .drawMethod("WEIGHTED")
                .status(EventStatus.OPEN)
                .createdBy(ownerMemberId)
                .createdAt(Instant.now())
                .build());
        eventId = event.getEventId();

        redisTemplate.opsForValue().set(EntryRedisKeys.status(eventId), "OPEN");
        redisTemplate.opsForValue().set(EntryRedisKeys.endAt(eventId), String.valueOf(FAR_FUTURE_MILLIS));

        throughputUserIds = new ArrayList<>(USER_COUNT);
        for (int i = 0; i < USER_COUNT; i++) {
            throughputUserIds.add(createUserWithBalance("부하테스트 응모자", THROUGHPUT_BALANCE));
        }
        contentionUserId = createUserWithBalance("경합테스트 응모자", CONTENTION_BALANCE);
    }

    private Long createUserWithBalance(String name, long balance) {
        Member member = memberRepository.saveAndFlush(new Member(name, null, null, MemberRole.USER));
        Long memberId = member.getMemberId();
        userTicketBalanceRepository.saveAndFlush(UserTicketBalance.builder()
                .memberId(memberId).creatorId(creatorId).balance(balance).updatedAt(Instant.now()).build());
        redisTemplate.opsForValue().set(TicketRedisKeys.balance(creatorId, memberId), String.valueOf(balance));
        return memberId;
    }

    @AfterEach
    void cleanUp() {
        if (creatorId != null) {
            jdbcTemplate.update("DELETE FROM ticket_ledger WHERE creator_id = ?", creatorId);
            jdbcTemplate.update("DELETE FROM event_entry WHERE event_id = ?", eventId);
            jdbcTemplate.update("DELETE FROM user_ticket_balance WHERE creator_id = ?", creatorId);
            jdbcTemplate.update("DELETE FROM event WHERE event_id = ?", eventId);
            jdbcTemplate.update("DELETE FROM creator WHERE creator_id = ?", creatorId);
        }
        List<Long> memberIds = new ArrayList<>();
        if (throughputUserIds != null) {
            memberIds.addAll(throughputUserIds);
        }
        if (contentionUserId != null) {
            memberIds.add(contentionUserId);
        }
        if (ownerMemberId != null) {
            memberIds.add(ownerMemberId);
        }
        for (Long memberId : memberIds) {
            jdbcTemplate.update("DELETE FROM member WHERE member_id = ?", memberId);
        }

        List<String> keys = new ArrayList<>();
        if (eventId != null) {
            keys.add(EntryRedisKeys.status(eventId));
            keys.add(EntryRedisKeys.endAt(eventId));
        }
        for (Long memberId : memberIds) {
            keys.add(TicketRedisKeys.balance(creatorId, memberId));
        }
        if (!keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
    }

    @Test
    void 서로_다른_사용자_동시_응모의_처리량을_측정한다() throws InterruptedException {
        List<String> requestIds = throughputUserIds.stream().map(id -> UUID.randomUUID().toString()).toList();
        long[] latenciesMillis = new long[USER_COUNT];
        AtomicInteger successCount = new AtomicInteger();

        Instant start = Instant.now();
        runConcurrently(USER_COUNT, index -> {
            long callStart = System.nanoTime();
            EntrySpendResult result = entrySpendService.spend(
                    eventId, throughputUserIds.get(index), creatorId, requestIds.get(index), 1);
            latenciesMillis[index] = (System.nanoTime() - callStart) / 1_000_000;
            if (result.code() == EntrySpendResultCode.SUCCESS) {
                successCount.incrementAndGet();
            } else {
                log.warn("예상치 못한 결과코드: userId={}, code={}", throughputUserIds.get(index), result.code());
            }
        });
        Duration elapsed = Duration.between(start, Instant.now());

        awaitEntryCount(USER_COUNT);
        report(elapsed, latenciesMillis, successCount.get());

        assertThat(successCount.get()).isEqualTo(USER_COUNT);
        assertNoNegativeBalance();
        assertEntryAndLedgerCount(USER_COUNT);
    }

    /**
     * 취합v1.5.4 §14 시나리오 5·6. 한 사용자의 같은 Redis 키·같은 DB 행에 동시 요청이 몰리므로
     * Lua 원자성이 깨지면 SUCCESS가 잔액보다 많아지거나 Balance가 음수로 내려간다.
     */
    @Test
    void 보유량을_초과한_동시_응모에도_잔액만큼만_성공하고_음수가_되지_않는다() throws InterruptedException {
        List<String> requestIds = new ArrayList<>(CONTENTION_REQUESTS);
        for (int i = 0; i < CONTENTION_REQUESTS; i++) {
            requestIds.add(UUID.randomUUID().toString());
        }
        long[] latenciesMillis = new long[CONTENTION_REQUESTS];
        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger insufficientCount = new AtomicInteger();
        AtomicInteger otherCount = new AtomicInteger();

        Instant start = Instant.now();
        runConcurrently(CONTENTION_REQUESTS, index -> {
            long callStart = System.nanoTime();
            EntrySpendResult result = entrySpendService.spend(
                    eventId, contentionUserId, creatorId, requestIds.get(index), 1);
            latenciesMillis[index] = (System.nanoTime() - callStart) / 1_000_000;
            switch (result.code()) {
                case SUCCESS -> successCount.incrementAndGet();
                case INSUFFICIENT_BALANCE -> insufficientCount.incrementAndGet();
                default -> {
                    otherCount.incrementAndGet();
                    log.warn("예상치 못한 결과코드: code={}", result.code());
                }
            }
        });
        Duration elapsed = Duration.between(start, Instant.now());

        awaitEntryCount((int) CONTENTION_BALANCE);
        log.info("[NFR-06 경합 시나리오] 동시 요청={}, 보유 잔액={}, SUCCESS={}, INSUFFICIENT_BALANCE={}, 기타={}",
                CONTENTION_REQUESTS, CONTENTION_BALANCE, successCount.get(), insufficientCount.get(), otherCount.get());
        report(elapsed, latenciesMillis, successCount.get());

        assertThat(otherCount.get()).isZero();
        assertThat(successCount.get()).isEqualTo((int) CONTENTION_BALANCE);
        assertThat(insufficientCount.get()).isEqualTo(CONTENTION_REQUESTS - (int) CONTENTION_BALANCE);

        // Redis는 정확히 0까지만 내려가야 한다(음수 금지).
        String redisBalance = redisTemplate.opsForValue().get(TicketRedisKeys.balance(creatorId, contentionUserId));
        assertThat(redisBalance).isEqualTo("0");

        assertNoNegativeBalance();
        assertEntryAndLedgerCount((int) CONTENTION_BALANCE);
        Long dbBalance = jdbcTemplate.queryForObject(
                "SELECT balance FROM user_ticket_balance WHERE member_id = ? AND creator_id = ?",
                Long.class, contentionUserId, creatorId);
        assertThat(dbBalance).isZero();
    }

    private void runConcurrently(int count, java.util.function.IntConsumer task) {
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> futures = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                int index = i;
                futures.add(executor.submit(() -> task.accept(index)));
            }
            for (Future<?> future : futures) {
                future.get();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("동시 호출이 중단됐습니다.", e);
        } catch (ExecutionException e) {
            throw new AssertionError("동시 호출 실패", e);
        }
    }

    /** SPEND Consumer(concurrency=1)가 기대 건수만큼 event_entry를 반영할 때까지 최대 30초 기다린다. */
    private void awaitEntryCount(int expected) throws InterruptedException {
        Instant deadline = Instant.now().plusSeconds(30);
        while (Instant.now().isBefore(deadline)) {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM event_entry WHERE event_id = ?", Integer.class, eventId);
            if (count != null && count >= expected) {
                return;
            }
            TimeUnit.MILLISECONDS.sleep(200);
        }
        throw new AssertionError("SPEND Consumer가 30초 안에 Entry %d건을 반영하지 못했습니다.".formatted(expected));
    }

    private void assertNoNegativeBalance() {
        Integer negativeCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM user_ticket_balance WHERE creator_id = ? AND balance < 0",
                Integer.class, creatorId);
        assertThat(negativeCount).isZero();
    }

    private void assertEntryAndLedgerCount(int expected) {
        Integer entryTotal = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM event_entry WHERE event_id = ?", Integer.class, eventId);
        Integer entryDistinct = jdbcTemplate.queryForObject(
                "SELECT COUNT(DISTINCT request_id) FROM event_entry WHERE event_id = ?", Integer.class, eventId);
        assertThat(entryTotal).isEqualTo(expected);
        assertThat(entryDistinct).isEqualTo(entryTotal);

        Integer ledgerTotal = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ticket_ledger WHERE creator_id = ? AND type = 'SPEND'",
                Integer.class, creatorId);
        Integer ledgerDistinct = jdbcTemplate.queryForObject(
                "SELECT COUNT(DISTINCT request_id) FROM ticket_ledger WHERE creator_id = ? AND type = 'SPEND'",
                Integer.class, creatorId);
        assertThat(ledgerTotal).isEqualTo(expected);
        assertThat(ledgerDistinct).isEqualTo(ledgerTotal);
    }

    private void report(Duration elapsed, long[] latenciesMillis, int successCount) {
        long[] sorted = latenciesMillis.clone();
        Arrays.sort(sorted);
        int total = sorted.length;
        double tps = total / Math.max(elapsed.toMillis() / 1000.0, 0.001);
        log.info("""
                        [NFR-06 부하 테스트 결과] 동시 요청 수={}, 성공={}
                        elapsed={}ms, TPS={}
                        p50={}ms, p95={}ms, p99={}ms, max={}ms""",
                total, successCount, elapsed.toMillis(), String.format("%.1f", tps),
                percentile(sorted, 0.50), percentile(sorted, 0.95), percentile(sorted, 0.99),
                sorted[total - 1]);
    }

    private long percentile(long[] sorted, double p) {
        int index = (int) Math.ceil(p * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(index, sorted.length - 1))];
    }
}
