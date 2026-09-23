package kr.co.cking.drawing.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;

import java.time.LocalDateTime;
import kr.co.cking.drawing.domain.DrawingVisibility;
import kr.co.cking.drawing.repository.DrawingRepository;
import kr.co.cking.event.repository.EventRepository;
import kr.co.cking.notification.application.WinnerNotificationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** Notification 생성 실패가 공개와 Event 전이를 함께 Rollback하는지 검증한다. */
@SpringBootTest
class PublicationServiceRollbackIntegrationTest {

    private static final long ADMIN_ID = 91201L;
    private static final long CREATOR_OWNER_ID = 91202L;
    private static final long CREATOR_ID = 92201L;
    private static final long EVENT_ID = 93201L;
    private static final long SNAPSHOT_ID = 94201L;
    private static final long SEED_ID = 95201L;
    private static final long DRAWING_ID = 96201L;

    @Autowired
    private PublicationService publicationService;

    @Autowired
    private DrawingRepository drawingRepository;

    @Autowired
    private EventRepository eventRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private WinnerNotificationService winnerNotificationService;

    @BeforeEach
    void setUp() {
        cleanUp();
        jdbcTemplate.update("INSERT INTO member (member_id, name, role) VALUES (?, ?, ?)",
                ADMIN_ID, "관리자", "ADMIN");
        jdbcTemplate.update("INSERT INTO member (member_id, name, role) VALUES (?, ?, ?)",
                CREATOR_OWNER_ID, "크리에이터", "USER");
        jdbcTemplate.update("INSERT INTO creator (creator_id, member_id, name) VALUES (?, ?, ?)",
                CREATOR_ID, CREATOR_OWNER_ID, "테스트 크리에이터");
        jdbcTemplate.update("INSERT INTO draw_seed (id, seed_value) VALUES (?, ?)", SEED_ID, "seed".getBytes());
        jdbcTemplate.update("""
                INSERT INTO event (
                    event_id, creator_id, title, start_at, end_at, winner_count,
                    draw_method, status, closed_at, request_id, created_by
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, EVENT_ID, CREATOR_ID, "공개 Rollback 통합 테스트",
                LocalDateTime.of(2026, 9, 1, 0, 0), LocalDateTime.of(2026, 9, 15, 0, 0),
                1, "WEIGHTED", "DRAW_COMPLETED", LocalDateTime.of(2026, 9, 15, 0, 0),
                "00000000-0000-0000-0000-000000000040", CREATOR_OWNER_ID);
        jdbcTemplate.update("""
                INSERT INTO draw_snapshot (
                    id, event_id, candidate_count, total_ticket_count, winner_count,
                    draw_method, algorithm_version, snapshot_hash, verification_status
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, SNAPSHOT_ID, EVENT_ID, 1, 10L, 1, "WEIGHTED", "WEIGHTED_V1", "c".repeat(64), "VERIFIED");
        jdbcTemplate.update("""
                INSERT INTO drawing (
                    id, event_id, draw_no, draw_type, snapshot_id, seed_id, draw_method,
                    algorithm_version, winner_count, status, visibility, requested_by
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, DRAWING_ID, EVENT_ID, 0, "INITIAL", SNAPSHOT_ID, SEED_ID, "WEIGHTED",
                "WEIGHTED_V1", 1, "COMPLETED", DrawingVisibility.PRIVATE.name(), ADMIN_ID);
        jdbcTemplate.update("""
                INSERT INTO winner (event_id, drawing_id, member_id, rank_in_drawing, applied_ticket_count)
                VALUES (?, ?, ?, ?, ?)
                """, EVENT_ID, DRAWING_ID, ADMIN_ID, 1, 10L);
    }

    @AfterEach
    void tearDown() {
        cleanUp();
    }

    @Test
    void Notification_생성이_실패하면_Drawing_공개와_Event_전이도_Rollback된다() {
        doThrow(new RuntimeException("알림 저장 실패"))
                .when(winnerNotificationService)
                .createInitialWinnerNotifications(org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyList());

        assertThatThrownBy(() -> publicationService.publish(DRAWING_ID, ADMIN_ID))
                .isInstanceOf(RuntimeException.class);

        assertThat(drawingRepository.findById(DRAWING_ID).orElseThrow().getVisibility())
                .isEqualTo(DrawingVisibility.PRIVATE);
        assertThat(eventRepository.findById(EVENT_ID).orElseThrow().getStatus().name())
                .isEqualTo("DRAW_COMPLETED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM notification WHERE drawing_id = ?", Integer.class, DRAWING_ID
        )).isZero();
    }

    private void cleanUp() {
        jdbcTemplate.update("DELETE n FROM notification n JOIN winner w ON n.winner_id = w.id WHERE w.drawing_id = ?", DRAWING_ID);
        jdbcTemplate.update("DELETE FROM winner WHERE drawing_id = ?", DRAWING_ID);
        jdbcTemplate.update("DELETE FROM draw_attempt_history WHERE drawing_id = ?", DRAWING_ID);
        jdbcTemplate.update("DELETE FROM drawing WHERE id = ?", DRAWING_ID);
        jdbcTemplate.update("DELETE FROM draw_snapshot WHERE id = ?", SNAPSHOT_ID);
        jdbcTemplate.update("DELETE FROM event WHERE event_id = ?", EVENT_ID);
        jdbcTemplate.update("DELETE FROM draw_seed WHERE id = ?", SEED_ID);
        jdbcTemplate.update("DELETE FROM creator WHERE creator_id = ? OR member_id IN (?, ?)",
                CREATOR_ID, ADMIN_ID, CREATOR_OWNER_ID);
        jdbcTemplate.update("DELETE FROM member WHERE member_id IN (?, ?)", ADMIN_ID, CREATOR_OWNER_ID);
    }
}
