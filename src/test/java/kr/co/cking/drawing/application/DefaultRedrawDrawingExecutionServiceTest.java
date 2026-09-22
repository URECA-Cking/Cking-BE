package kr.co.cking.drawing.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Optional;
import kr.co.cking.drawing.domain.Drawing;
import kr.co.cking.drawing.domain.DrawingStatus;
import kr.co.cking.drawing.domain.DrawingType;
import kr.co.cking.drawing.repository.DrawingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DefaultRedrawDrawingExecutionServiceTest {

    @Mock RedrawDrawingPreparationService preparationService;
    @Mock DrawingRetryService retryService;
    @Mock DrawingRepository drawingRepository;

    private DefaultRedrawDrawingExecutionService service;

    @BeforeEach
    void setUp() {
        service = new DefaultRedrawDrawingExecutionService(
                preparationService, retryService, drawingRepository);
    }

    @Test
    void 준비_단계가_종결_결과를_반환하면_실행하지_않는다() {
        RedrawDrawingExecutionResult terminal = RedrawDrawingExecutionResult.noCandidates();
        when(preparationService.prepare(10L, 1L, 20L, 2))
                .thenReturn(RedrawDrawingPreparation.terminal(terminal));

        assertThat(service.execute(10L, 1L, 20L, 2)).isEqualTo(terminal);
        verifyNoInteractions(retryService, drawingRepository);
    }

    @Test
    void 준비한_첫_Attempt를_공통_Drawing_실행_경로로_완료한다() {
        DrawingRetryRequest request = new DrawingRetryRequest(30L, 1);
        when(preparationService.prepare(10L, 1L, 20L, 2))
                .thenReturn(RedrawDrawingPreparation.started(request));
        when(retryService.executePrepared(request)).thenReturn(new DrawingRetryResult(
                30L, 40L, DrawingType.REDRAW, DrawingStatus.COMPLETED, 1, 2));

        assertThat(service.execute(10L, 1L, 20L, 2))
                .isEqualTo(RedrawDrawingExecutionResult.executed(30L));
    }

    @Test
    void 실행_실패가_FAILED로_보존되면_같은_Drawing_ID를_반환한다() {
        DrawingRetryRequest request = new DrawingRetryRequest(30L, 1);
        Drawing failed = mock(Drawing.class);
        when(preparationService.prepare(10L, 1L, 20L, 2))
                .thenReturn(RedrawDrawingPreparation.started(request));
        when(retryService.executePrepared(request)).thenThrow(new IllegalStateException("engine down"));
        when(drawingRepository.findById(30L)).thenReturn(Optional.of(failed));
        when(failed.getStatus()).thenReturn(DrawingStatus.FAILED);

        assertThat(service.execute(10L, 1L, 20L, 2))
                .isEqualTo(RedrawDrawingExecutionResult.failed(30L));
    }

    @Test
    void 실패_상태_보존까지_실패하면_원래_예외를_전파한다() {
        DrawingRetryRequest request = new DrawingRetryRequest(30L, 1);
        IllegalStateException failure = new IllegalStateException("persistence down");
        when(preparationService.prepare(10L, 1L, 20L, 2))
                .thenReturn(RedrawDrawingPreparation.started(request));
        when(retryService.executePrepared(request)).thenThrow(failure);
        when(drawingRepository.findById(30L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.execute(10L, 1L, 20L, 2)).isSameAs(failure);
    }
}
