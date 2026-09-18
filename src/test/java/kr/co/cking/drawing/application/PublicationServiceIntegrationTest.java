package kr.co.cking.drawing.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import kr.co.cking.drawing.application.DrawingPublicationResult.PublicationOutcome;
import kr.co.cking.drawing.domain.DrawingVisibility;
import kr.co.cking.drawing.repository.DrawingRepository;
import kr.co.cking.event.repository.EventRepository;
import kr.co.cking.notification.domain.NotificationType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/** 공개 결과와 당첨 Notification의 최초 생성·멱등성·동시성 처리를 실제 DB로 검증한다. */
@SpringBootTest
class PublicationServiceIntegrationTest {

    private static final long ADMIN_ID = 91301L;
    private static final long CREATOR_OWNER_ID = 91302L;
    private static final long FIRST_WINNER_MEMBER_ID = 91303L;
    private static final long SECOND_WINNER_MEMBER_ID = 91304L;
    private static final long CREATOR_ID = 92301L;
    private static final long EVENT_ID = 93301L;
    private static final long SNAPSHOT_ID = 94301L;
    private static final long SEED_ID = 95301L;
    private static final long DRAWING_ID = 96301L;

    @Autowired
    private PublicationService publicationService;

    @Autowired
    private DrawingRepository drawingRepository;

    @Autowired
    private EventRepository eventRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        cleanUp();
        jdbcTemplate.update("INSERT INTO member (member_id, name, role) VALUES (?, ?, ?)", ADMIN_ID, "관리자", "ADMIN");
        jdbcTemplate.update("INSERT INTO member (member_id, name, role) VALUES (?, ?, ?)", CREATOR_OWNER_ID, "크리에이터", "USER");
        jdbcTemplate.update("INSERT INTO member (member_id, name, role) VALUES (?, ?, ?)", FIRST_WINNER_MEMBER_ID, "당첨자1", "USER");
        jdbcTemplate.update("INSERT INTO member (member_id, name, role) VALUES (?, ?, ?)", SECOND_WINNER_MEMBER_ID, "당첨자2", "USER");
        jdbcTemplate.update("INSERT INTO creator (creator_id, member_id, name) VALUES (?, ?, ?)", CREATOR_ID, CREATOR_OWNER_ID, "테스트 크리에이터");
        jdbcTemplate.update("INSERT INTO draw_seed (id, seed_value) VALUES (?, ?)", SEED_ID, "seed".getBytes());
        jdbcTemplate.update("""
                INSERT INTO event (
                    event_id, creator_id, title, start_at, end_at, winner_count,
                    draw_method, status, closed_at, request_id, created_by
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, EVENT_ID, CREATOR_ID, "공개 Notification 통합 테스트",
                LocalDateTime.of(2026, 9, 1, 0, 0), LocalDateTime.of(2026, 9, 15, 0, 0),
                2, "WEIGHTED", "DRAW_COMPLETED", LocalDateTime.of(2026, 9, 15, 0, 0),
                "00000000-0000-0000-0000-000000000050", CREATOR_OWNER_ID);
        jdbcTemplate.update("""
                INSERT INTO draw_snapshot (
                    id, event_id, candidate_count, total_ticket_count, winner_count,
                    draw_method, algorithm_version, snapshot_hash, verification_status
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, SNAPSHOT_ID, EVENT_ID, 2, 20L, 2, "WEIGHTED", "WEIGHTED_V1", "d".repeat(64), "VERIFIED");
        jdbcTemplate.update("""
                INSERT INTO drawing (
                    id, event_id, draw_no, draw_type, snapshot_id, seed_id, draw_method,
                    algorithm_version, winner_count, status, visibility, requested_by
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, DRAWING_ID, EVENT_ID, 0, "INITIAL", SNAPSHOT_ID, SEED_ID, "WEIGHTED",
                "WEIGHTED_V1", 2, "COMPLETED", DrawingVisibility.PRIVATE.name(), ADMIN_ID);
        jdbcTemplate.update("""
                INSERT INTO winner (event_id, drawing_id, member_id, rank_in_drawing, applied_ticket_count)
                VALUES (?, ?, ?, ?, ?), (?, ?, ?, ?, ?)
                """, EVENT_ID, DRAWING_ID, FIRST_WINNER_MEMBER_ID, 1, 10L,
                EVENT_ID, DRAWING_ID, SECOND_WINNER_MEMBER_ID, 2, 10L);
    }

    @AfterEach
    void tearDown() {
        cleanUp();
    }

    @Test
    void 최초_공개는_Winner별_Notification을_한_번씩_생성한다() {
        DrawingPublicationResult result = publicationService.publish(DRAWING_ID, ADMIN_ID);

        assertThat(result.outcome()).isEqualTo(PublicationOutcome.PUBLISHED);
        assertThat(notificationCount()).isEqualTo(2);
        assertThat(notificationTypes()).containsOnly(NotificationType.INITIAL_WINNER.name());
        assertThat(drawingRepository.findById(DRAWING_ID).orElseThrow().getVisibility())
                .isEqualTo(DrawingVisibility.PUBLIC);
        assertThat(eventRepository.findById(EVENT_ID).orElseThrow().getStatus().name()).isEqualTo("PUBLISHED");
    }

    @Test
    void 재공개는_Notification을_추가_생성하지_않는다() {
        publicationService.publish(DRAWING_ID, ADMIN_ID);

        DrawingPublicationResult replay = publicationService.publish(DRAWING_ID, ADMIN_ID);

        assertThat(replay.outcome()).isEqualTo(PublicationOutcome.ALREADY_PUBLISHED);
        assertThat(notificationCount()).isEqualTo(2);
    }

    @Test
    void 동시_공개는_한_번만_전이하고_Notification을_중복_생성하지_않는다() throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            Callable<DrawingPublicationResult> task = () -> {
                ready.countDown();
                start.await();
                return publicationService.publish(DRAWING_ID, ADMIN_ID);
            };
            Future<DrawingPublicationResult> first = executor.submit(task);
            Future<DrawingPublicationResult> second = executor.submit(task);

            ready.await();
            start.countDown();

            assertThat(List.of(first.get().outcome(), second.get().outcome()))
                    .containsExactlyInAnyOrder(PublicationOutcome.PUBLISHED, PublicationOutcome.ALREADY_PUBLISHED);
        }

        assertThat(notificationCount()).isEqualTo(2);
        assertThat(drawingRepository.findById(DRAWING_ID).orElseThrow().getVisibility())
                .isEqualTo(DrawingVisibility.PUBLIC);
        assertThat(eventRepository.findById(EVENT_ID).orElseThrow().getStatus().name()).isEqualTo("PUBLISHED");
    }

    private int notificationCount() {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM notification WHERE drawing_id = ?", Integer.class, DRAWING_ID
        );
    }

    private List<String> notificationTypes() {
        return jdbcTemplate.queryForList(
                "SELECT type FROM notification WHERE drawing_id = ?", String.class, DRAWING_ID
        );
    }

    private void cleanUp() {
        jdbcTemplate.update("DELETE n FROM notification n JOIN winner w ON n.winner_id = w.id WHERE w.drawing_id = ?", DRAWING_ID);
        jdbcTemplate.update("DELETE FROM winner WHERE drawing_id = ?", DRAWING_ID);
        jdbcTemplate.update("DELETE FROM drawing WHERE id = ?", DRAWING_ID);
        jdbcTemplate.update("DELETE FROM draw_snapshot WHERE id = ?", SNAPSHOT_ID);
        jdbcTemplate.update("DELETE FROM event WHERE event_id = ?", EVENT_ID);
        jdbcTemplate.update("DELETE FROM draw_seed WHERE id = ?", SEED_ID);
        jdbcTemplate.update("DELETE FROM creator WHERE creator_id = ?", CREATOR_ID);
        jdbcTemplate.update("DELETE FROM member WHERE member_id IN (?, ?, ?, ?)",
                ADMIN_ID, CREATOR_OWNER_ID, FIRST_WINNER_MEMBER_ID, SECOND_WINNER_MEMBER_ID);
    }
}
