package kr.co.cking.snapshot.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import kr.co.cking.snapshot.application.OfficialSnapshotService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
class JdbcSnapshotSourceQueryRepositoryTest {

    private static final long MEMBER_ID = 8_221_001L;
    private static final long CREATOR_ID = 8_221_101L;
    private static final long FIRST_OLD_EVENT_ID = 8_221_201L;
    private static final long SECOND_OLD_EVENT_ID = 8_221_202L;
    private static final long BOUNDARY_EVENT_ID = 8_221_203L;
    private static final long RECENT_EVENT_ID = 8_221_204L;
    private static final long CLOSING_EVENT_ID = 8_221_205L;
    private static final long SNAPSHOT_EXISTS_EVENT_ID = 8_221_206L;
    private static final Instant CLOSED_BEFORE = Instant.parse("2026-09-22T03:00:00Z");

    @Autowired
    private SnapshotSourceQueryRepository sourceQueryRepository;

    @Autowired
    private OfficialSnapshotService officialSnapshotService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        cleanUp();
        jdbcTemplate.update("INSERT INTO member (member_id, name, role) VALUES (?, ?, ?)",
                MEMBER_ID, "Snapshot 복구 소유자", "USER");
        jdbcTemplate.update("INSERT INTO creator (creator_id, member_id, name) VALUES (?, ?, ?)",
                CREATOR_ID, MEMBER_ID, "Snapshot 복구 크리에이터");

        insertEvent(FIRST_OLD_EVENT_ID, "CLOSED", CLOSED_BEFORE.minusSeconds(120), 201);
        insertEvent(SECOND_OLD_EVENT_ID, "CLOSED", CLOSED_BEFORE.minusSeconds(60), 202);
        insertEvent(BOUNDARY_EVENT_ID, "CLOSED", CLOSED_BEFORE, 203);
        insertEvent(RECENT_EVENT_ID, "CLOSED", CLOSED_BEFORE.plusMillis(1), 204);
        insertEvent(CLOSING_EVENT_ID, "CLOSING", CLOSED_BEFORE.minusSeconds(180), 205);
        insertEvent(SNAPSHOT_EXISTS_EVENT_ID, "CLOSED", CLOSED_BEFORE.minusSeconds(180), 206);
        officialSnapshotService.createIfAbsent(SNAPSHOT_EXISTS_EVENT_ID);
    }

    @AfterEach
    void tearDown() {
        cleanUp();
    }

    @Test
    void CLOSED_유예시간과_Snapshot_존재여부를_적용해_오래된_Event부터_조회한다() {
        List<Long> eventIds = sourceQueryRepository.findMissingOfficialSnapshotEventIds(CLOSED_BEFORE, 10);

        assertThat(eventIds).containsExactly(FIRST_OLD_EVENT_ID, SECOND_OLD_EVENT_ID, BOUNDARY_EVENT_ID);
    }

    @Test
    void 설정한_배치_크기만큼만_조회한다() {
        List<Long> eventIds = sourceQueryRepository.findMissingOfficialSnapshotEventIds(CLOSED_BEFORE, 2);

        assertThat(eventIds).containsExactly(FIRST_OLD_EVENT_ID, SECOND_OLD_EVENT_ID);
    }

    private void insertEvent(long eventId, String status, Instant closedAt, int requestNumber) {
        jdbcTemplate.update("""
                INSERT INTO event (
                    event_id, creator_id, title, start_at, end_at, winner_count,
                    draw_method, status, closed_at, request_id, created_by
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                eventId,
                CREATOR_ID,
                "Snapshot 복구 조회 " + eventId,
                LocalDateTime.of(2026, 9, 1, 0, 0),
                LocalDateTime.of(2026, 9, 15, 0, 0),
                1,
                "WEIGHTED",
                status,
                LocalDateTime.ofInstant(closedAt, ZoneOffset.UTC),
                "00000000-0000-0000-0000-000000000" + requestNumber,
                MEMBER_ID
        );
    }

    private void cleanUp() {
        jdbcTemplate.update("""
                DELETE candidate
                FROM draw_snapshot_candidate candidate
                JOIN draw_snapshot snapshot ON snapshot.id = candidate.snapshot_id
                WHERE snapshot.event_id BETWEEN ? AND ?
                """, FIRST_OLD_EVENT_ID, SNAPSHOT_EXISTS_EVENT_ID);
        jdbcTemplate.update("""
                DELETE prize
                FROM draw_snapshot_prize prize
                JOIN draw_snapshot snapshot ON snapshot.id = prize.snapshot_id
                WHERE snapshot.event_id BETWEEN ? AND ?
                """, FIRST_OLD_EVENT_ID, SNAPSHOT_EXISTS_EVENT_ID);
        jdbcTemplate.update("DELETE FROM draw_snapshot WHERE event_id BETWEEN ? AND ?",
                FIRST_OLD_EVENT_ID, SNAPSHOT_EXISTS_EVENT_ID);
        jdbcTemplate.update("DELETE FROM event WHERE event_id BETWEEN ? AND ?",
                FIRST_OLD_EVENT_ID, SNAPSHOT_EXISTS_EVENT_ID);
        jdbcTemplate.update("DELETE FROM creator WHERE creator_id = ?", CREATOR_ID);
        jdbcTemplate.update("DELETE FROM member WHERE member_id = ?", MEMBER_ID);
    }
}
