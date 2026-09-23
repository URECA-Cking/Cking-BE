package kr.co.cking.drawing.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;

import java.time.LocalDateTime;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import kr.co.cking.drawing.domain.DrawingVisibility;
import kr.co.cking.drawing.repository.DrawingRepository;
import kr.co.cking.event.application.service.EventCommandService;
import kr.co.cking.event.repository.EventRepository;

/**
 * Drawing.publish()가 이미 실행된 뒤 EventCommandService.publish()가 실패하는 경우에도
 * 두 변경이 같은 Tx로 함께 Rollback되는지 검증한다(코드리뷰 지적: 기존 실패 케이스는 Event
 * 상태 사전 검증에서 막혀 Drawing이 애초에 바뀌지 않았을 뿐, 실제 중간 실패 Rollback을
 * 증명하지 못했다). EventCommandService만 Mock으로 교체하고 나머지는 실제 DB를 사용한다.
 */
@SpringBootTest
class DrawingPublicationServiceRollbackIntegrationTest {

    private static final long ADMIN_ID = 91101L;
    private static final long CREATOR_OWNER_ID = 91102L;
    private static final long CREATOR_ID = 92101L;
    private static final long EVENT_ID = 93101L;
    private static final long SNAPSHOT_ID = 94101L;
    private static final long SEED_ID = 95101L;
    private static final long DRAWING_ID = 96101L;

    @Autowired
    private DrawingPublicationService service;

    @Autowired
    private DrawingRepository drawingRepository;

    @Autowired
    private EventRepository eventRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private EventCommandService eventCommandService;

    @BeforeEach
    void setUp() {
        cleanUp();
        jdbcTemplate.update("INSERT INTO member (member_id, name, role) VALUES (?, ?, ?)",
                ADMIN_ID, "관리자", "ADMIN");
        jdbcTemplate.update("INSERT INTO member (member_id, name, role) VALUES (?, ?, ?)",
                CREATOR_OWNER_ID, "크리에이터", "USER");
        jdbcTemplate.update("INSERT INTO creator (creator_id, member_id, name) VALUES (?, ?, ?)",
                CREATOR_ID, CREATOR_OWNER_ID, "테스트 크리에이터");
        jdbcTemplate.update("INSERT INTO draw_seed (id, seed_value) VALUES (?, ?)",
                SEED_ID, "seed".getBytes());
        jdbcTemplate.update("""
                INSERT INTO event (
                    event_id, creator_id, title, start_at, end_at, winner_count,
                    draw_method, status, closed_at, request_id, created_by
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                EVENT_ID, CREATOR_ID, "Rollback 통합 테스트",
                LocalDateTime.of(2026, 9, 1, 0, 0), LocalDateTime.of(2026, 9, 15, 0, 0),
                2, "WEIGHTED", "DRAW_COMPLETED", LocalDateTime.of(2026, 9, 15, 0, 0),
                "00000000-0000-0000-0000-000000000030", CREATOR_OWNER_ID
        );
        jdbcTemplate.update("""
                INSERT INTO draw_snapshot (
                    id, event_id, candidate_count, total_ticket_count, winner_count,
                    draw_method, algorithm_version, snapshot_hash, verification_status
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                SNAPSHOT_ID, EVENT_ID, 1, 10L, 2, "WEIGHTED", "WEIGHTED_V1", "b".repeat(64), "VERIFIED"
        );
        jdbcTemplate.update("""
                INSERT INTO drawing (
                    id, event_id, draw_no, draw_type, snapshot_id, seed_id, draw_method,
                    algorithm_version, winner_count, status, visibility, requested_by
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                DRAWING_ID, EVENT_ID, 0, "INITIAL", SNAPSHOT_ID, SEED_ID, "WEIGHTED",
                "WEIGHTED_V1", 2, "COMPLETED", DrawingVisibility.PRIVATE.name(), ADMIN_ID
        );
    }

    @AfterEach
    void tearDown() {
        cleanUp();
    }

    @Test
    void EventCommandService_publish가_실패하면_이미_적용한_Drawing_공개도_Rollback된다() {
        doThrow(new RuntimeException("강제 실패")).when(eventCommandService).publish(EVENT_ID);

        assertThatThrownBy(() -> service.publish(DRAWING_ID, ADMIN_ID))
                .isInstanceOf(RuntimeException.class);

        assertThat(drawingRepository.findById(DRAWING_ID).orElseThrow().getVisibility())
                .isEqualTo(DrawingVisibility.PRIVATE);
        assertThat(drawingRepository.findById(DRAWING_ID).orElseThrow().getPublishedAt()).isNull();
        assertThat(eventRepository.findById(EVENT_ID).orElseThrow().getStatus().name())
                .isEqualTo("DRAW_COMPLETED");
    }

    private void cleanUp() {
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
