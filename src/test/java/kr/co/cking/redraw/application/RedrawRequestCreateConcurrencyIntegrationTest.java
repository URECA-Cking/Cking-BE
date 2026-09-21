package kr.co.cking.redraw.application;

import static org.assertj.core.api.Assertions.assertThat;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import kr.co.cking.common.exception.BusinessException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/** 같은 Event·멱등 키의 병렬 재시도가 기존 요청을 재사용하는지 실제 DB로 검증한다. */
@SpringBootTest
class RedrawRequestCreateConcurrencyIntegrationTest {

    private static final long ADMIN_ID = 98101L;
    private static final long WINNER_MEMBER_ID = 98102L;
    private static final long CREATOR_ID = 98201L;
    private static final long EVENT_ID = 98301L;
    private static final long SNAPSHOT_ID = 98401L;
    private static final long SEED_ID = 98501L;
    private static final long DRAWING_ID = 98601L;
    private static final long WINNER_ID = 98701L;
    private static final String IDEMPOTENCY_KEY = "d2719c4a-1f9b-4dc4-a656-9a4bb37d8e70";
    private static final String SECOND_IDEMPOTENCY_KEY = "e3829e5b-2f0c-5ed5-b767-ab5ac48e9f81";

    @Autowired
    private RedrawRequestCreateService redrawRequestCreateService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** 각 테스트 전에 PUBLISHED Event의 DECLINED Winner 한 명을 준비한다. */
    @BeforeEach
    void setUp() {
        cleanUp();
        jdbcTemplate.update("INSERT INTO member (member_id, name, role) VALUES (?, ?, ?)", ADMIN_ID, "관리자", "ADMIN");
        jdbcTemplate.update("INSERT INTO member (member_id, name, role) VALUES (?, ?, ?)", WINNER_MEMBER_ID, "결원 당첨자", "USER");
        jdbcTemplate.update("INSERT INTO creator (creator_id, member_id, name) VALUES (?, ?, ?)",
                CREATOR_ID, ADMIN_ID, "동시성 테스트 크리에이터");
        jdbcTemplate.update("""
                INSERT INTO event (
                    event_id, creator_id, title, start_at, end_at, winner_count, draw_method,
                    status, created_by, request_id
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, EVENT_ID, CREATOR_ID, "재추첨 멱등성 동시성 테스트",
                Instant.parse("2026-09-01T00:00:00Z"), Instant.parse("2026-09-15T00:00:00Z"),
                1, "WEIGHTED", "PUBLISHED", ADMIN_ID, "00000000-0000-0000-0000-000000098301");
        jdbcTemplate.update("""
                INSERT INTO draw_snapshot (
                    id, event_id, candidate_count, total_ticket_count, winner_count,
                    draw_method, algorithm_version, snapshot_hash, verification_status
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, SNAPSHOT_ID, EVENT_ID, 0, 0, 1, "WEIGHTED", "WEIGHTED_V1", "a".repeat(64), "UNVERIFIED");
        jdbcTemplate.update("INSERT INTO draw_seed (id, seed_value) VALUES (?, ?)", SEED_ID, new byte[]{1, 2, 3});
        jdbcTemplate.update("""
                INSERT INTO drawing (
                    id, event_id, draw_no, draw_type, snapshot_id, seed_id, draw_method,
                    algorithm_version, winner_count, status, visibility, requested_by
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, DRAWING_ID, EVENT_ID, 0, "INITIAL", SNAPSHOT_ID, SEED_ID, "WEIGHTED",
                "WEIGHTED_V1", 1, "COMPLETED", "PUBLIC", ADMIN_ID);
        jdbcTemplate.update("""
                INSERT INTO winner (id, event_id, drawing_id, member_id, rank_in_drawing, applied_ticket_count)
                VALUES (?, ?, ?, ?, ?, ?)
                """, WINNER_ID, EVENT_ID, DRAWING_ID, WINNER_MEMBER_ID, 1, 1);
        jdbcTemplate.update("INSERT INTO winner_management (winner_id, status) VALUES (?, ?)", WINNER_ID, "DECLINED");
    }

    @AfterEach
    void tearDown() {
        cleanUp();
    }

    /** 같은 멱등 키 요청을 동시에 보내도 잠금 후 기존 요청을 재사용해 하나만 생성한다. */
    @Test
    void 같은_멱등_키의_동시_재시도는_기존_요청을_재사용한다() throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<RedrawRequestCreateResult> first = executor.submit(
                    () -> createAfterSignal(ready, start, IDEMPOTENCY_KEY)
            );
            Future<RedrawRequestCreateResult> second = executor.submit(
                    () -> createAfterSignal(ready, start, IDEMPOTENCY_KEY)
            );
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<RedrawRequestCreateResult> results = List.of(
                    first.get(10, TimeUnit.SECONDS),
                    second.get(10, TimeUnit.SECONDS)
            );

            assertThat(results).extracting(RedrawRequestCreateResult::redrawRequestId).containsOnly(results.getFirst().redrawRequestId());
            assertThat(results).extracting(RedrawRequestCreateResult::created).containsExactlyInAnyOrder(true, false);
            assertThat(redrawRequestCount()).isEqualTo(1);
        }
    }

    /** 서로 다른 멱등 키의 동시 요청도 같은 Winner 결원을 중복 점유하지 못하게 한다. */
    @Test
    void 다른_멱등_키의_동시_요청은_결원을_한번만_점유한다() throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<String> first = executor.submit(() -> createOrReturnError(ready, start, IDEMPOTENCY_KEY));
            Future<String> second = executor.submit(() -> createOrReturnError(ready, start, SECOND_IDEMPOTENCY_KEY));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("SUCCESS", "NO_REDRAW_VACANCY");
            assertThat(redrawRequestCount()).isEqualTo(1);
            assertThat(vacancyCount()).isEqualTo(1);
        }
    }

    /** 시작 신호 뒤 지정한 멱등 키로 생성 명령을 실행한다. */
    private RedrawRequestCreateResult createAfterSignal(
            CountDownLatch ready,
            CountDownLatch start,
            String idempotencyKey
    ) throws InterruptedException {
        ready.countDown();
        start.await();
        return redrawRequestCreateService.create(new RedrawRequestCreateCommand(
                ADMIN_ID, EVENT_ID, "당첨자 포기에 따른 재추첨", idempotencyKey
        ));
    }

    /** 병렬 생성 결과를 성공 또는 도메인 오류 코드 문자열로 변환한다. */
    private String createOrReturnError(CountDownLatch ready, CountDownLatch start, String idempotencyKey)
            throws InterruptedException {
        try {
            createAfterSignal(ready, start, idempotencyKey);
            return "SUCCESS";
        } catch (BusinessException exception) {
            return exception.getErrorCode().code();
        }
    }

    /** Event에 생성된 RedrawRequest 수를 반환한다. */
    private int redrawRequestCount() {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM redraw_request WHERE event_id = ?", Integer.class, EVENT_ID
        );
    }

    /** Event에 확정된 RedrawRequestVacancy 행 수를 반환한다. */
    private int vacancyCount() {
        return jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM redraw_request_vacancy vacancy
                JOIN redraw_request request ON request.id = vacancy.redraw_request_id
                WHERE request.event_id = ?
                """, Integer.class, EVENT_ID);
    }

    /** 외래 키 의존성의 역순으로 테스트 fixture를 정리한다. */
    private void cleanUp() {
        jdbcTemplate.update("""
                DELETE vacancy FROM redraw_request_vacancy vacancy
                JOIN redraw_request request ON request.id = vacancy.redraw_request_id
                WHERE request.event_id = ?
                """, EVENT_ID);
        jdbcTemplate.update("DELETE FROM redraw_request WHERE event_id = ?", EVENT_ID);
        jdbcTemplate.update("DELETE FROM winner_management WHERE winner_id = ?", WINNER_ID);
        jdbcTemplate.update("DELETE FROM winner WHERE id = ?", WINNER_ID);
        jdbcTemplate.update("DELETE FROM drawing WHERE id = ?", DRAWING_ID);
        jdbcTemplate.update("DELETE FROM draw_snapshot WHERE id = ?", SNAPSHOT_ID);
        jdbcTemplate.update("DELETE FROM draw_seed WHERE id = ?", SEED_ID);
        jdbcTemplate.update("DELETE FROM event WHERE event_id = ?", EVENT_ID);
        jdbcTemplate.update("DELETE FROM creator WHERE creator_id = ?", CREATOR_ID);
        jdbcTemplate.update("DELETE FROM member WHERE member_id IN (?, ?)", ADMIN_ID, WINNER_MEMBER_ID);
    }
}
