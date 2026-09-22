package kr.co.cking.redraw.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import kr.co.cking.redraw.domain.RedrawExecutionStatus;
import kr.co.cking.snapshot.application.SnapshotHashGenerator;
import kr.co.cking.snapshot.application.SnapshotHashInput;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** 시스템3 Snapshot 무결성 실패가 REDRAW 생성 롤백과 요청 실패 이력으로 종결되는지 검증한다. */
@SpringBootTest
class RedrawRequestExecutionSnapshotFailureIntegrationTest extends RedrawRequestCreateIntegrationFixture {

    @Autowired
    private RedrawRequestExecutionService redrawRequestExecutionService;

    @Test
    void Snapshot_Hash_불일치면_REDRAW_결과를_롤백하고_FAILED_이력을_저장한다() {
        Long redrawRequestId = redrawRequestCreateService.create(command(newIdempotencyKey())).redrawRequestId();
        redrawRequestReviewService.approve(adminId(), redrawRequestId);
        long eventId = jdbcTemplate.queryForObject(
                "SELECT event_id FROM redraw_request WHERE id = ?", Long.class, redrawRequestId
        );
        String validHash = new SnapshotHashGenerator().generate(new SnapshotHashInput(
                eventId, 1, "WEIGHTED", "WEIGHTED_V1", List.of()
        )).value();
        jdbcTemplate.update("UPDATE draw_snapshot SET snapshot_hash = ? WHERE event_id = ?", validHash, eventId);
        jdbcTemplate.update("UPDATE draw_snapshot SET snapshot_hash = ? WHERE event_id = ?", "b".repeat(64), eventId);

        RedrawRequestExecutionResult result = redrawRequestExecutionService.execute(adminId(), redrawRequestId);

        assertThat(result).isEqualTo(new RedrawRequestExecutionResult(
                redrawRequestId, RedrawExecutionStatus.FAILED, null));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT execution_status FROM redraw_request WHERE id = ?", String.class, redrawRequestId
        )).isEqualTo("FAILED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM drawing WHERE redraw_request_id = ?", Integer.class, redrawRequestId
        )).isZero();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM winner winner
                JOIN drawing drawing ON drawing.id = winner.drawing_id
                WHERE drawing.redraw_request_id = ?
                """, Integer.class, redrawRequestId)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT failure_code FROM redraw_execution_history WHERE redraw_request_id = ?", String.class, redrawRequestId
        )).isEqualTo("SNAPSHOT_HASH_MISMATCH");
    }
}
