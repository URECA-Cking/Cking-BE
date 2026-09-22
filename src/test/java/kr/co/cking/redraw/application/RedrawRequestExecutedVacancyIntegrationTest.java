package kr.co.cking.redraw.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.drawing.application.RedrawDrawingExecutionResult;
import kr.co.cking.drawing.application.RedrawDrawingExecutionService;
import kr.co.cking.redraw.domain.RedrawErrorCode;
import kr.co.cking.redraw.domain.RedrawExecutionStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

/** 실행 완료 요청이 점유한 원본 결원이 새 재추첨 요청으로 재사용되지 않는지 검증한다. */
@SpringBootTest
@Import(RedrawRequestExecutedVacancyIntegrationTest.SuccessfulRedrawExecutionConfiguration.class)
class RedrawRequestExecutedVacancyIntegrationTest extends RedrawRequestCreateIntegrationFixture {

    @Autowired
    private RedrawRequestExecutionService redrawRequestExecutionService;

    @Test
    void 실행_완료된_요청의_결원은_새_재추첨_요청으로_다시_점유할_수_없다() {
        Long firstRequestId = redrawRequestCreateService.create(command(newIdempotencyKey())).redrawRequestId();
        redrawRequestReviewService.approve(adminId(), firstRequestId);

        RedrawRequestExecutionResult execution = redrawRequestExecutionService.execute(adminId(), firstRequestId);

        assertThat(execution.executionStatus()).isEqualTo(RedrawExecutionStatus.EXECUTED);
        assertThatThrownBy(() -> redrawRequestCreateService.create(command(newIdempotencyKey())))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", RedrawErrorCode.NO_REDRAW_VACANCY);
        assertThat(redrawRequestCount()).isOne();
        assertThat(vacancyCount()).isOne();
    }

    /** 성공 실행 결과만 고정해 결원 점유 수명 검증을 시스템3 구현과 분리한다. */
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
