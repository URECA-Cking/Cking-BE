package kr.co.cking.redraw.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import kr.co.cking.drawing.application.RedrawDrawingExecutionService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.transaction.annotation.Transactional;

/** Drawing을 만들기 전 실패는 요청을 PENDING으로 유지해 실행 API로 재시도할 수 있는지 검증한다. */
@SpringBootTest
@Import(RedrawRequestExecutionFailurePersistenceIntegrationTest.FailingRedrawExecutionConfiguration.class)
class RedrawRequestExecutionFailurePersistenceIntegrationTest extends RedrawRequestCreateIntegrationFixture {

    @Autowired
    private RedrawRequestExecutionService redrawRequestExecutionService;

    @Test
    void Drawing_생성_전_시스템3_실행_예외는_요청을_PENDING으로_유지한다() {
        Long redrawRequestId = redrawRequestCreateService.create(command(newIdempotencyKey())).redrawRequestId();
        redrawRequestReviewService.approve(adminId(), redrawRequestId);

        assertThatThrownBy(() -> redrawRequestExecutionService.execute(adminId(), redrawRequestId))
                .isInstanceOf(RedrawExecutionInfrastructureInitializationFailureException.class);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT execution_status FROM redraw_request WHERE id = ?", String.class, redrawRequestId
        )).isEqualTo("PENDING");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT completed_at FROM redraw_request WHERE id = ?", java.sql.Timestamp.class, redrawRequestId
        )).isNull();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM redraw_execution_history WHERE redraw_request_id = ?", Integer.class, redrawRequestId
        )).isZero();
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
        public kr.co.cking.drawing.application.RedrawDrawingExecutionResult execute(
                Long redrawRequestId, Long adminId, Long originalDrawingId, int vacancyCount
        ) {
            throw new RedrawExecutionInfrastructureInitializationFailureException("시스템3 실행 실패");
        }
    }

    static class RedrawExecutionInfrastructureInitializationFailureException extends RuntimeException {
        RedrawExecutionInfrastructureInitializationFailureException(String message) {
            super(message);
        }
    }
}
