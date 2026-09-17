package kr.co.cking.drawing.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.drawing.domain.DrawingVisibility;
import kr.co.cking.drawing.repository.DrawingRepository;
import kr.co.cking.event.domain.EventErrorCode;
import kr.co.cking.event.repository.EventRepository;

/**
 * Drawing 공개와 Event {@code DRAW_COMPLETED→PUBLISHED} 전이가 실제 DB Tx로 함께
 * 커밋·롤백되는지 검증한다(코드리뷰 지적: Mock 기반 단위 테스트만으로는 Tx 경계를 증명하지 못함).
 */
@SpringBootTest
class DrawingPublicationServiceIntegrationTest {

    private static final long ADMIN_ID = 91001L;
    private static final long CREATOR_OWNER_ID = 91002L;
    private static final long CREATOR_ID = 92001L;
    private static final long EVENT_ID = 93001L;
    private static final long SNAPSHOT_ID = 94001L;
    private static final long SEED_ID = 95001L;
    private static final long DRAWING_ID = 96001L;

    @Autowired
    private DrawingPublicationService service;

    @Autowired
    private DrawingRepository drawingRepository;

    @Autowired
    private EventRepository eventRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        cleanUp();
        jdbcTemplate.update("""
                INSERT INTO member (member_id, name, role) VALUES (?, ?, ?)
                """, ADMIN_ID, "관리자", "ADMIN");
        jdbcTemplate.update("""
                INSERT INTO member (member_id, name, role) VALUES (?, ?, ?)
                """, CREATOR_OWNER_ID, "크리에이터", "USER");
        jdbcTemplate.update("""
                INSERT INTO creator (creator_id, member_id, name) VALUES (?, ?, ?)
                """, CREATOR_ID, CREATOR_OWNER_ID, "테스트 크리에이터");
        jdbcTemplate.update("""
                INSERT INTO draw_seed (id, seed_value) VALUES (?, ?)
                """, SEED_ID, "seed".getBytes());
    }

    @AfterEach
    void tearDown() {
        cleanUp();
    }

    @Test
    void 최초_공개_성공_시_Drawing과_Event가_함께_커밋된다() {
        insertEvent("DRAW_COMPLETED");
        insertSnapshot();
        insertDrawing(DrawingVisibility.PRIVATE);

        service.publish(DRAWING_ID, ADMIN_ID);

        assertThat(drawingRepository.findById(DRAWING_ID).orElseThrow().getVisibility())
                .isEqualTo(DrawingVisibility.PUBLIC);
        assertThat(eventRepository.findById(EVENT_ID).orElseThrow().getStatus().name())
                .isEqualTo("PUBLISHED");
    }

    @Test
    void Event가_DRAW_COMPLETED가_아니면_Drawing을_바꾸지_않고_실패한다() {
        insertEvent("CLOSED");
        insertSnapshot();
        insertDrawing(DrawingVisibility.PRIVATE);

        assertThatThrownBy(() -> service.publish(DRAWING_ID, ADMIN_ID))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", EventErrorCode.INVALID_STATE);

        assertThat(drawingRepository.findById(DRAWING_ID).orElseThrow().getVisibility())
                .isEqualTo(DrawingVisibility.PRIVATE);
        assertThat(eventRepository.findById(EVENT_ID).orElseThrow().getStatus().name())
                .isEqualTo("CLOSED");
    }

    @Test
    void 동시_공개_요청은_한_번만_전이되고_나머지는_멱등하게_성공한다() throws Exception {
        insertEvent("DRAW_COMPLETED");
        insertSnapshot();
        insertDrawing(DrawingVisibility.PRIVATE);

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            Callable<DrawingPublicationResult> task = () -> {
                ready.countDown();
                start.await();
                return service.publish(DRAWING_ID, ADMIN_ID);
            };
            Future<DrawingPublicationResult> first = executor.submit(task);
            Future<DrawingPublicationResult> second = executor.submit(task);

            ready.await();
            start.countDown();

            // 두 요청 모두 예외 없이 PUBLIC을 반환해야 한다(하나는 최초 전이, 하나는 멱등 성공).
            assertThat(first.get().visibility()).isEqualTo(DrawingVisibility.PUBLIC);
            assertThat(second.get().visibility()).isEqualTo(DrawingVisibility.PUBLIC);
        }

        assertThat(drawingRepository.findById(DRAWING_ID).orElseThrow().getVisibility())
                .isEqualTo(DrawingVisibility.PUBLIC);
        assertThat(eventRepository.findById(EVENT_ID).orElseThrow().getStatus().name())
                .isEqualTo("PUBLISHED");
    }

    @Test
    void 멱등_재요청은_추가_변경_없이_동일_상태를_반환한다() {
        insertEvent("DRAW_COMPLETED");
        insertSnapshot();
        insertDrawing(DrawingVisibility.PRIVATE);

        service.publish(DRAWING_ID, ADMIN_ID);
        Instant firstPublishedAt = drawingRepository.findById(DRAWING_ID).orElseThrow().getPublishedAt();

        DrawingPublicationResult replay = service.publish(DRAWING_ID, ADMIN_ID);

        // DB 컬럼은 DATETIME(6)라 나노초 이하가 잘리므로, 두 값 모두 DB에서 다시 읽어 비교한다.
        assertThat(replay.publishedAt()).isEqualTo(firstPublishedAt);
        assertThat(eventRepository.findById(EVENT_ID).orElseThrow().getStatus().name())
                .isEqualTo("PUBLISHED");
    }

    private void insertEvent(String status) {
        jdbcTemplate.update("""
                INSERT INTO event (
                    event_id, creator_id, title, start_at, end_at, winner_count,
                    draw_method, status, closed_at, request_id, created_by
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                EVENT_ID,
                CREATOR_ID,
                "공개 통합 테스트",
                LocalDateTime.of(2026, 9, 1, 0, 0),
                LocalDateTime.of(2026, 9, 15, 0, 0),
                2,
                "WEIGHTED",
                status,
                LocalDateTime.of(2026, 9, 15, 0, 0),
                "00000000-0000-0000-0000-000000000020",
                CREATOR_OWNER_ID
        );
    }

    private void insertSnapshot() {
        jdbcTemplate.update("""
                INSERT INTO draw_snapshot (
                    id, event_id, candidate_count, total_ticket_count, winner_count,
                    draw_method, algorithm_version, snapshot_hash, verification_status
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                SNAPSHOT_ID, EVENT_ID, 1, 10L, 2, "WEIGHTED", "WEIGHTED_V1", "a".repeat(64), "VERIFIED"
        );
    }

    private void insertDrawing(DrawingVisibility visibility) {
        jdbcTemplate.update("""
                INSERT INTO drawing (
                    id, event_id, draw_no, draw_type, snapshot_id, seed_id, draw_method,
                    algorithm_version, winner_count, status, visibility, requested_by
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                DRAWING_ID, EVENT_ID, 0, "INITIAL", SNAPSHOT_ID, SEED_ID, "WEIGHTED",
                "WEIGHTED_V1", 2, "COMPLETED", visibility.name(), ADMIN_ID
        );
    }

    private void cleanUp() {
        jdbcTemplate.update("DELETE FROM drawing WHERE id = ?", DRAWING_ID);
        jdbcTemplate.update("DELETE FROM draw_snapshot WHERE id = ?", SNAPSHOT_ID);
        jdbcTemplate.update("DELETE FROM event WHERE event_id = ?", EVENT_ID);
        jdbcTemplate.update("DELETE FROM draw_seed WHERE id = ?", SEED_ID);
        jdbcTemplate.update("DELETE FROM creator WHERE creator_id = ?", CREATOR_ID);
        jdbcTemplate.update("DELETE FROM member WHERE member_id IN (?, ?)", ADMIN_ID, CREATOR_OWNER_ID);
    }
}
