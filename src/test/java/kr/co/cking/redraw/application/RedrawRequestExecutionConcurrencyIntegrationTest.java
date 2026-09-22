package kr.co.cking.redraw.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.drawing.application.RedrawDrawingExecutionResult;
import kr.co.cking.drawing.application.RedrawDrawingExecutionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

/** 같은 RedrawRequest 동시 실행이 행 잠금으로 한 번만 종결되는지 실제 DB에서 검증한다. */
@SpringBootTest
@Import(RedrawRequestExecutionConcurrencyIntegrationTest.SuccessfulRedrawExecutionConfiguration.class)
class RedrawRequestExecutionConcurrencyIntegrationTest extends RedrawRequestCreateIntegrationFixture {

    @Autowired
    private RedrawRequestExecutionService redrawRequestExecutionService;

    @Test
    void 동시_실행은_Drawing을_중복_생성하지_않고_같은_EXECUTED_결과를_반환한다() throws Exception {
        Long redrawRequestId = redrawRequestCreateService.create(command(newIdempotencyKey())).redrawRequestId();
        redrawRequestReviewService.approve(adminId(), redrawRequestId);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<String> first = executor.submit(() -> executeAfterSignal(ready, start, redrawRequestId));
            Future<String> second = executor.submit(() -> executeAfterSignal(ready, start, redrawRequestId));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
                    .containsExactly("EXECUTED", "EXECUTED");
        }
        assertThat(jdbcTemplate.queryForObject(
                "SELECT execution_status FROM redraw_request WHERE id = ?", String.class, redrawRequestId
        )).isEqualTo("EXECUTED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM redraw_execution_history WHERE redraw_request_id = ?", Integer.class, redrawRequestId
        )).isOne();
    }

    private String executeAfterSignal(CountDownLatch ready, CountDownLatch start, Long redrawRequestId)
            throws InterruptedException {
        ready.countDown();
        start.await();
        try {
            return redrawRequestExecutionService.execute(adminId(), redrawRequestId).executionStatus().name();
        } catch (BusinessException exception) {
            return exception.getErrorCode().code();
        }
    }

    /** 동시성 검증에서는 시스템3 결과만 성공으로 고정해 시스템4의 잠금 경계를 분리한다. */
    @TestConfiguration
    static class SuccessfulRedrawExecutionConfiguration {

        @Bean
        @Primary
        RedrawDrawingExecutionService successfulRedrawDrawingExecutionService() {
            return (redrawRequestId, adminId, originalDrawingId, vacancyCount) ->
                    RedrawDrawingExecutionResult.executed(1L);
        }
    }
}
