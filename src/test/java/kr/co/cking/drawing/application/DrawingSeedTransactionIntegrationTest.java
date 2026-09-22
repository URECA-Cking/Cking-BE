package kr.co.cking.drawing.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import kr.co.cking.drawing.domain.Drawing;
import kr.co.cking.drawing.domain.DrawingSnapshotContract;
import kr.co.cking.drawing.repository.DrawSeedRepository;
import kr.co.cking.drawing.repository.DrawingRepository;
import kr.co.cking.snapshot.application.VerifiedSnapshot;
import kr.co.cking.snapshot.application.VerifiedSnapshotTestFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Import(DrawingSeedTransactionIntegrationTest.SeedDrawingRollbackProbe.class)
class DrawingSeedTransactionIntegrationTest {

    private static final long ADMIN_ID = 107_101L;
    private static final long CREATOR_OWNER_ID = 107_102L;
    private static final long CREATOR_ID = 107_201L;
    private static final long EVENT_ID = 107_301L;
    private static final long SNAPSHOT_ID = 107_401L;

    @Autowired
    private SeedDrawingRollbackProbe rollbackProbe;

    @Autowired
    private DrawingSeedService drawingSeedService;

    @Autowired
    private DrawSeedRepository drawSeedRepository;

    @Autowired
    private DrawingRepository drawingRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        cleanUp();
        jdbcTemplate.update("INSERT INTO member (member_id, name, role) VALUES (?, ?, ?)",
                ADMIN_ID, "Seed 관리자", "ADMIN");
        jdbcTemplate.update("INSERT INTO member (member_id, name, role) VALUES (?, ?, ?)",
                CREATOR_OWNER_ID, "Seed 크리에이터", "USER");
        jdbcTemplate.update("INSERT INTO creator (creator_id, member_id, name) VALUES (?, ?, ?)",
                CREATOR_ID, CREATOR_OWNER_ID, "Seed 테스트 크리에이터");
        jdbcTemplate.update("""
                INSERT INTO event (
                    event_id, creator_id, title, start_at, end_at, winner_count,
                    draw_method, status, closed_at, request_id, created_by
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                EVENT_ID, CREATOR_ID, "Seed 트랜잭션 테스트",
                LocalDateTime.of(2026, 9, 1, 0, 0), LocalDateTime.of(2026, 9, 15, 0, 0),
                1, "WEIGHTED", "CLOSED", LocalDateTime.of(2026, 9, 15, 0, 0),
                "00000000-0000-0000-0000-000000000107", CREATOR_OWNER_ID
        );
        jdbcTemplate.update("""
                INSERT INTO draw_snapshot (
                    id, event_id, candidate_count, total_ticket_count, winner_count,
                    draw_method, algorithm_version, snapshot_hash, verification_status
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                SNAPSHOT_ID, EVENT_ID, 1, 10L, 1,
                "WEIGHTED", "WEIGHTED_V1", "1".repeat(64), "VERIFIED"
        );
    }

    @AfterEach
    void tearDown() {
        cleanUp();
    }

    @Test
    void Drawing_저장_실패_트랜잭션은_신규_Seed도_함께_Rollback한다() {
        long seedCountBefore = drawSeedRepository.count();
        DrawingSnapshotContract contract = DrawingSnapshotContract.from(verifiedSnapshot());

        assertThatThrownBy(() -> rollbackProbe.createDrawingAndFail(contract, ADMIN_ID))
                .isInstanceOf(ForcedRollbackException.class);

        assertThat(drawingRepository.existsByEventIdAndDrawNo(EVENT_ID, 0)).isFalse();
        assertThat(drawSeedRepository.count()).isEqualTo(seedCountBefore);
    }

    @Test
    void 호출자_Transaction이_없으면_INITIAL_Seed를_저장하지_않는다() {
        long seedCountBefore = drawSeedRepository.count();

        assertThatThrownBy(drawingSeedService::createForInitial)
                .isInstanceOf(IllegalTransactionStateException.class);

        assertThat(drawSeedRepository.count()).isEqualTo(seedCountBefore);
    }

    @Test
    void 호출자_Transaction이_없으면_REDRAW_Seed를_저장하지_않는다() {
        long seedCountBefore = drawSeedRepository.count();

        assertThatThrownBy(() -> drawingSeedService.createForRedraw(1L))
                .isInstanceOf(IllegalTransactionStateException.class);

        assertThat(drawSeedRepository.count()).isEqualTo(seedCountBefore);
    }

    private VerifiedSnapshot verifiedSnapshot() {
        return VerifiedSnapshotTestFactory.create(
                SNAPSHOT_ID,
                EVENT_ID,
                1,
                "WEIGHTED",
                "WEIGHTED_V1"
        );
    }

    private void cleanUp() {
        jdbcTemplate.update("DELETE h FROM draw_attempt_history h JOIN drawing d ON h.drawing_id = d.id WHERE d.event_id = ?", EVENT_ID);
        jdbcTemplate.update("DELETE FROM drawing WHERE event_id = ?", EVENT_ID);
        jdbcTemplate.update("DELETE FROM draw_snapshot WHERE id = ?", SNAPSHOT_ID);
        jdbcTemplate.update("DELETE FROM event WHERE event_id = ?", EVENT_ID);
        jdbcTemplate.update("DELETE FROM creator WHERE creator_id = ?", CREATOR_ID);
        jdbcTemplate.update("DELETE FROM member WHERE member_id IN (?, ?)", ADMIN_ID, CREATOR_OWNER_ID);
    }

    static class SeedDrawingRollbackProbe {

        private final DrawingSeedService drawingSeedService;
        private final DrawingRepository drawingRepository;

        SeedDrawingRollbackProbe(
                DrawingSeedService drawingSeedService,
                DrawingRepository drawingRepository
        ) {
            this.drawingSeedService = drawingSeedService;
            this.drawingRepository = drawingRepository;
        }

        @Transactional
        public void createDrawingAndFail(DrawingSnapshotContract contract, Long requestedBy) {
            PersistedDrawingSeed seed = drawingSeedService.createForInitial();
            drawingRepository.saveAndFlush(Drawing.createInitial(contract, seed.seedId(), requestedBy));
            // Seed와 Drawing 저장 이후의 실패 상황 재현.
            throw new ForcedRollbackException();
        }
    }

    private static final class ForcedRollbackException extends RuntimeException {
    }
}
