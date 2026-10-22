package kr.co.cking.redraw.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.snapshot.domain.SnapshotErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** Drawing 생성 전 Snapshot 무결성 실패는 요청을 PENDING으로 남기는지 검증한다. */
@SpringBootTest
class RedrawRequestExecutionSnapshotFailureIntegrationTest extends RedrawRequestCreateIntegrationFixture {

    @Autowired
    private RedrawRequestExecutionService redrawRequestExecutionService;

    @Test
    void Snapshot_Hash_불일치면_REDRAW_생성없이_요청은_PENDING으로_남는다() {
        Long redrawRequestId = redrawRequestCreateService.create(command(newIdempotencyKey())).redrawRequestId();
        redrawRequestReviewService.approve(adminId(), redrawRequestId);
        jdbcTemplate.update("""
                UPDATE draw_snapshot snapshot
                JOIN redraw_request request ON request.event_id = snapshot.event_id
                SET snapshot.snapshot_hash = ?
                WHERE request.id = ?
                """, "b".repeat(64), redrawRequestId);

        assertThatThrownBy(() -> redrawRequestExecutionService.execute(adminId(), redrawRequestId))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(SnapshotErrorCode.SNAPSHOT_HASH_MISMATCH);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT execution_status FROM redraw_request WHERE id = ?", String.class, redrawRequestId
        )).isEqualTo("PENDING");
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
                "SELECT COUNT(*) FROM redraw_execution_history WHERE redraw_request_id = ?", Integer.class, redrawRequestId
        )).isZero();
    }
}
