package kr.co.cking.redraw.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

import java.time.Instant;
import kr.co.cking.redraw.repository.RedrawRequestVacancyRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/** RedrawRequest와 Vacancy 저장의 트랜잭션 원자성 및 실패 후 멱등 재시도를 실제 DB로 검증한다. */
@SpringBootTest
class RedrawRequestCreateRollbackIntegrationTest {

    private static final long ADMIN_ID = 99101L;
    private static final long WINNER_MEMBER_ID = 99102L;
    private static final long CREATOR_ID = 99201L;
    private static final long EVENT_ID = 99301L;
    private static final long SNAPSHOT_ID = 99401L;
    private static final long SEED_ID = 99501L;
    private static final long DRAWING_ID = 99601L;
    private static final long WINNER_ID = 99701L;
    private static final String IDEMPOTENCY_KEY = "f493af6c-3a1d-6fe6-c878-bc6bd59fa092";

    @Autowired
    private RedrawRequestCreateService redrawRequestCreateService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoSpyBean
    private RedrawRequestVacancyRepository redrawRequestVacancyRepository;

    /** 각 테스트 전에 PUBLISHED Event와 DECLINED Winner 한 명을 준비한다. */
    @BeforeEach
    void setUp() {
        cleanUp();
        jdbcTemplate.update("INSERT INTO member (member_id, name, role) VALUES (?, ?, ?)", ADMIN_ID, "관리자", "ADMIN");
        jdbcTemplate.update("INSERT INTO member (member_id, name, role) VALUES (?, ?, ?)", WINNER_MEMBER_ID, "결원 당첨자", "USER");
        jdbcTemplate.update("INSERT INTO creator (creator_id, member_id, name) VALUES (?, ?, ?)",
                CREATOR_ID, ADMIN_ID, "원자성 테스트 크리에이터");
        jdbcTemplate.update("""
                INSERT INTO event (
                    event_id, creator_id, title, start_at, end_at, winner_count, draw_method,
                    status, created_by, request_id
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, EVENT_ID, CREATOR_ID, "재추첨 원자성 테스트",
                Instant.parse("2026-09-01T00:00:00Z"), Instant.parse("2026-09-15T00:00:00Z"),
                1, "WEIGHTED", "PUBLISHED", ADMIN_ID, "00000000-0000-0000-0000-000000099301");
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

    /** 각 테스트 뒤 외래 키 의존성의 역순으로 fixture를 정리한다. */
    @AfterEach
    void tearDown() {
        cleanUp();
    }

    /** Vacancy 저장 실패는 Request까지 롤백하고 같은 멱등 키의 복구 재시도를 허용한다. */
    @Test
    void Vacancy_저장_실패_후_같은_멱등_키로_재시도할_수_있다() {
        doThrow(new RuntimeException("Vacancy 저장 강제 실패"))
                .when(redrawRequestVacancyRepository).saveAll(any());

        assertThatThrownBy(() -> redrawRequestCreateService.create(command()))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("강제 실패");
        assertThat(redrawRequestCount()).isZero();
        assertThat(vacancyCount()).isZero();

        reset(redrawRequestVacancyRepository);

        RedrawRequestCreateResult retry = redrawRequestCreateService.create(command());

        assertThat(retry.created()).isTrue();
        assertThat(redrawRequestCount()).isEqualTo(1);
        assertThat(vacancyCount()).isEqualTo(1);
    }

    /** 테스트에서 재사용하는 유효한 RedrawRequest 생성 명령을 만든다. */
    private RedrawRequestCreateCommand command() {
        return new RedrawRequestCreateCommand(ADMIN_ID, EVENT_ID, "당첨자 포기에 따른 재추첨", IDEMPOTENCY_KEY);
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
