package kr.co.cking.drawing.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import kr.co.cking.drawing.domain.DrawingFailureStage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DrawingRetryServiceTest {

    @Mock DrawingRetryPreparationService preparationService;
    @Mock DrawingRetryExecutionService executionService;
    @Mock DrawingFailurePersistenceService failurePersistenceService;

    @Test
    void 결과_트랜잭션_실패_후_별도_경계에_실패를_기록한다() {
        DrawingRetryRequest request = new DrawingRetryRequest(10L, 2);
        IllegalStateException cause = new IllegalStateException("engine down");
        when(preparationService.prepare(10L, 1L)).thenReturn(request);
        when(executionService.execute(request)).thenThrow(
                DrawingExecutionFailure.at(DrawingFailureStage.DRAWING_ENGINE, cause));
        DrawingRetryService service = new DrawingRetryService(
                preparationService, executionService, failurePersistenceService);

        assertThatThrownBy(() -> service.retry(10L, 1L)).isSameAs(cause);

        verify(failurePersistenceService).recordFailure(
                request, DrawingFailureStage.DRAWING_ENGINE, "SYSTEM_ERROR", "engine down");
    }
}
