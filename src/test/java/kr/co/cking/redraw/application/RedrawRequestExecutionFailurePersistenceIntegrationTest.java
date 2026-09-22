package kr.co.cking.redraw.application;

import static org.assertj.core.api.Assertions.assertThat;

import kr.co.cking.drawing.application.RedrawDrawingExecutionResult;
import kr.co.cking.drawing.application.RedrawDrawingExecutionService;
import kr.co.cking.redraw.domain.RedrawExecutionStatus;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.transaction.annotation.Transactional;

/** 시스템3 실행 Tx가 롤백돼도 실패 상태와 이력이 독립적으로 커밋되는지 검증한다. */
@SpringBootTest
@Import(RedrawRequestExecutionFailurePersistenceIntegrationTest.FailingRedrawExecutionConfiguration.class)
class RedrawRequestExecutionFailurePersistenceIntegrationTest extends RedrawRequestCreateIntegrationFixture {

    @Autowired
    private RedrawRequestExecutionService redrawRequestExecutionService;

    @Test
    void 시스템3_실행_예외가_같은_물리_트랜잭션을_rollbackOnly로_만들어도_FAILED와_이력은_저장된다() {
        Long redrawRequestId = redrawRequestCreateService.create(command(newIdempotencyKey())).redrawRequestId();
        redrawRequestReviewService.approve(adminId(), redrawRequestId);

        RedrawRequestExecutionResult result = redrawRequestExecutionService.execute(adminId(), redrawRequestId);

        assertThat(result.executionStatus()).isEqualTo(RedrawExecutionStatus.FAILED);
        assertThat(result.redrawDrawingId()).isNull();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT execution_status FROM redraw_request WHERE id = ?", String.class, redrawRequestId
        )).isEqualTo("FAILED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT completed_at FROM redraw_request WHERE id = ?", java.sql.Timestamp.class, redrawRequestId
        )).isNotNull();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM redraw_execution_history WHERE redraw_request_id = ?", Integer.class, redrawRequestId
        )).isOne();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT failure_code FROM redraw_execution_history WHERE redraw_request_id = ?", String.class, redrawRequestId
        )).isEqualTo(IllegalStateException.class.getSimpleName());
    }

    /** 내부 REQUIRED 실행이 런타임 예외로 같은 물리 트랜잭션을 rollback-only로 만든다. */
    @TestConfiguration
    static class FailingRedrawExecutionConfiguration {

        @Bean
        @Primary
        RedrawDrawingExecutionService failingRedrawDrawingExecutionService() {
            return new FailingRedrawDrawingExecutionService();
        }
    }

    static class FailingRedrawDrawingExecutionService implements RedrawDrawingExecutionService {

        @Override
        @Transactional
        public RedrawDrawingExecutionResult execute(
                Long redrawRequestId, Long adminId, Long originalDrawingId, int vacancyCount
        ) {
            throw new IllegalStateException("시스템3 실행 실패");
        }
    }
}
