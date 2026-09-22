package kr.co.cking.drawing.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import kr.co.cking.drawing.domain.DrawAttemptHistory;
import kr.co.cking.drawing.domain.DrawAttemptStatus;
import kr.co.cking.drawing.domain.Drawing;
import kr.co.cking.drawing.domain.DrawingFailureStage;
import kr.co.cking.drawing.domain.DrawingStatus;
import kr.co.cking.drawing.repository.DrawAttemptHistoryRepository;
import kr.co.cking.drawing.repository.DrawingRepository;
import kr.co.cking.redraw.application.RedrawDrawingLifecycleService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DrawingFailurePersistenceServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-22T00:00:00Z");

    @Mock DrawingRepository drawingRepository;
    @Mock DrawAttemptHistoryRepository attemptRepository;
    @Mock RedrawDrawingLifecycleService redrawLifecycleService;
    @Mock Drawing drawing;

    @Test
    void 결과_Rollback_후_RUNNING_Drawing과_Attempt를_FAILED로_보존한다() {
        DrawAttemptHistory attempt = DrawAttemptHistory.started(10L, 2, 1L, NOW.minusSeconds(1));
        when(drawingRepository.findByIdForRetry(10L)).thenReturn(Optional.of(drawing));
        when(drawing.getStatus()).thenReturn(DrawingStatus.RUNNING);
        when(drawing.getAttemptCount()).thenReturn(2);
        when(attemptRepository.findByDrawingIdAndAttemptNo(10L, 2)).thenReturn(Optional.of(attempt));
        DrawingFailurePersistenceService service = new DrawingFailurePersistenceService(
                drawingRepository, attemptRepository, redrawLifecycleService,
                Clock.fixed(NOW, ZoneOffset.UTC));

        service.recordFailure(new DrawingRetryRequest(10L, 2), DrawingFailureStage.DRAWING_ENGINE,
                "SYSTEM_ERROR", "engine down");

        org.mockito.Mockito.verify(drawing).fail();
        assertThat(attempt.getStatus()).isEqualTo(DrawAttemptStatus.FAILED);
        assertThat(attempt.getFailureStage()).isEqualTo(DrawingFailureStage.DRAWING_ENGINE);
        assertThat(attempt.getFailureCode()).isEqualTo("SYSTEM_ERROR");
    }

    @Test
    void 오래된_STARTED_Attempt만_서버중단으로_종결한다() {
        DrawAttemptHistory attempt = DrawAttemptHistory.started(10L, 1, null, NOW.minusSeconds(700));
        when(drawingRepository.findByIdForRetry(10L)).thenReturn(Optional.of(drawing));
        when(drawing.getStatus()).thenReturn(DrawingStatus.RUNNING);
        when(attemptRepository.findFirstByDrawingIdOrderByAttemptNoDesc(10L)).thenReturn(Optional.of(attempt));
        DrawingFailurePersistenceService service = new DrawingFailurePersistenceService(
                drawingRepository, attemptRepository, redrawLifecycleService,
                Clock.fixed(NOW, ZoneOffset.UTC));

        boolean interrupted = service.markInterrupted(10L, NOW.minusSeconds(600));

        assertThat(interrupted).isTrue();
        assertThat(attempt.getFailureStage()).isEqualTo(DrawingFailureStage.SERVER_INTERRUPTED);
        org.mockito.Mockito.verify(drawing).fail();
    }
}
