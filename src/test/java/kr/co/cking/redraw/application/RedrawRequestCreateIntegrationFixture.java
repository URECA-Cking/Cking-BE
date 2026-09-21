package kr.co.cking.redraw.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreatorFactory;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;

/** RedrawRequest 생성 통합 테스트가 직접 생성한 DB fixture의 생명주기를 관리한다. */
abstract class RedrawRequestCreateIntegrationFixture {

    private static final String REASON = "당첨자 포기에 따른 재추첨";

    @Autowired
    protected RedrawRequestCreateService redrawRequestCreateService;

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    private Long adminId;
    private Long winnerMemberId;
    private Long creatorId;
    private Long eventId;
    private Long snapshotId;
    private Long seedId;
    private Long drawingId;
    private Long winnerId;

    /** 공유 DB에서 충돌하지 않도록 자동 생성 ID로 PUBLISHED Event와 DECLINED Winner를 만든다. */
    @BeforeEach
    void setUpFixture() {
        adminId = insertAndReturnId("INSERT INTO member (name, role) VALUES (?, ?)", "member_id", "관리자", "ADMIN");
        winnerMemberId = insertAndReturnId(
                "INSERT INTO member (name, role) VALUES (?, ?)", "member_id", "결원 당첨자", "USER"
        );
        creatorId = insertAndReturnId(
                "INSERT INTO creator (member_id, name) VALUES (?, ?)", "creator_id", adminId, "재추첨 테스트 크리에이터"
        );
        eventId = insertAndReturnId("""
                INSERT INTO event (
                    creator_id, title, start_at, end_at, winner_count, draw_method,
                    status, created_by, request_id
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, "event_id", creatorId, "재추첨 테스트", Instant.parse("2026-09-01T00:00:00Z"),
                Instant.parse("2026-09-15T00:00:00Z"), 1, "WEIGHTED", "PUBLISHED", adminId,
                UUID.randomUUID().toString());
        snapshotId = insertAndReturnId("""
                INSERT INTO draw_snapshot (
                    event_id, candidate_count, total_ticket_count, winner_count,
                    draw_method, algorithm_version, snapshot_hash, verification_status
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, "id", eventId, 0, 0, 1, "WEIGHTED", "WEIGHTED_V1", "a".repeat(64), "UNVERIFIED");
        seedId = insertAndReturnId("INSERT INTO draw_seed (seed_value) VALUES (?)", "id", new byte[]{1, 2, 3});
        drawingId = insertAndReturnId("""
                INSERT INTO drawing (
                    event_id, draw_no, draw_type, snapshot_id, seed_id, draw_method,
                    algorithm_version, winner_count, status, visibility, requested_by
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, "id", eventId, 0, "INITIAL", snapshotId, seedId, "WEIGHTED",
                "WEIGHTED_V1", 1, "COMPLETED", "PUBLIC", adminId);
        winnerId = insertAndReturnId("""
                INSERT INTO winner (event_id, drawing_id, member_id, rank_in_drawing, applied_ticket_count)
                VALUES (?, ?, ?, ?, ?)
                """, "id", eventId, drawingId, winnerMemberId, 1, 1);
        jdbcTemplate.update("INSERT INTO winner_management (winner_id, status) VALUES (?, ?)", winnerId, "DECLINED");
    }

    /** 테스트가 자동 생성한 fixture만 외래 키 의존성의 역순으로 삭제한다. */
    @AfterEach
    void tearDownFixture() {
        if (eventId != null) {
            jdbcTemplate.update("""
                    DELETE vacancy FROM redraw_request_vacancy vacancy
                    JOIN redraw_request request ON request.id = vacancy.redraw_request_id
                    WHERE request.event_id = ?
                    """, eventId);
            jdbcTemplate.update("DELETE FROM redraw_request WHERE event_id = ?", eventId);
        }
        if (winnerId != null) {
            jdbcTemplate.update("DELETE FROM winner_management WHERE winner_id = ?", winnerId);
            jdbcTemplate.update("DELETE FROM winner WHERE id = ?", winnerId);
        }
        if (drawingId != null) {
            jdbcTemplate.update("DELETE FROM drawing WHERE id = ?", drawingId);
        }
        if (snapshotId != null) {
            jdbcTemplate.update("DELETE FROM draw_snapshot WHERE id = ?", snapshotId);
        }
        if (seedId != null) {
            jdbcTemplate.update("DELETE FROM draw_seed WHERE id = ?", seedId);
        }
        if (eventId != null) {
            jdbcTemplate.update("DELETE FROM event WHERE event_id = ?", eventId);
        }
        if (creatorId != null) {
            jdbcTemplate.update("DELETE FROM creator WHERE creator_id = ?", creatorId);
        }
        if (adminId != null) {
            jdbcTemplate.update("DELETE FROM member WHERE member_id = ?", adminId);
        }
        if (winnerMemberId != null) {
            jdbcTemplate.update("DELETE FROM member WHERE member_id = ?", winnerMemberId);
        }
    }

    /** 테스트에서 지정한 멱등 키와 fixture Event로 유효한 생성 명령을 만든다. */
    protected RedrawRequestCreateCommand command(String idempotencyKey) {
        return new RedrawRequestCreateCommand(adminId, eventId, REASON, idempotencyKey);
    }

    /** fixture Event에 생성된 RedrawRequest 수를 반환한다. */
    protected int redrawRequestCount() {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM redraw_request WHERE event_id = ?", Integer.class, eventId
        );
    }

    /** fixture Event에 확정된 RedrawRequestVacancy 행 수를 반환한다. */
    protected int vacancyCount() {
        return jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM redraw_request_vacancy vacancy
                JOIN redraw_request request ON request.id = vacancy.redraw_request_id
                WHERE request.event_id = ?
                """, Integer.class, eventId);
    }

    /** INSERT 직후 JDBC 생성 키를 읽어 fixture가 소유한 행 식별자로 반환한다. */
    private long insertAndReturnId(String sql, String keyColumn, Object... values) {
        PreparedStatementCreatorFactory factory = new PreparedStatementCreatorFactory(sql);
        factory.setReturnGeneratedKeys(true);
        factory.setGeneratedKeysColumnNames(keyColumn);
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(factory.newPreparedStatementCreator(List.of(values)), keyHolder);
        return keyHolder.getKey().longValue();
    }
}
