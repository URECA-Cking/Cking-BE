package kr.co.cking.redraw.application;

import static org.assertj.core.api.Assertions.assertThat;

import kr.co.cking.redraw.domain.RedrawExecutionStatus;
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
        jdbcTemplate.update("""
                UPDATE draw_snapshot snapshot
                JOIN redraw_request request ON request.event_id = snapshot.event_id
                SET snapshot.snapshot_hash = ?
                WHERE request.id = ?
                """, "b".repeat(64), redrawRequestId);

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
