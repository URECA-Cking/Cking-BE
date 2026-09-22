package kr.co.cking.drawing.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.drawing.domain.DrawAttemptHistory;
import kr.co.cking.drawing.domain.Drawing;
import kr.co.cking.drawing.domain.DrawingErrorCode;
import kr.co.cking.drawing.domain.DrawingStatus;
import kr.co.cking.drawing.domain.DrawingType;
import kr.co.cking.drawing.domain.hash.DrawInputHashGenerator;
import kr.co.cking.drawing.domain.hash.DrawInputV2HashGenerator;
import kr.co.cking.drawing.repository.DrawAttemptHistoryRepository;
import kr.co.cking.drawing.repository.DrawingRepository;
import kr.co.cking.drawing.repository.RedrawDrawingQueryRepository;
import kr.co.cking.drawing.repository.RedrawExclusionRepository;
import kr.co.cking.event.application.EventDrawingQueryService;
import kr.co.cking.snapshot.application.SnapshotIntegrityService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 같은 Event의 REDRAW가 확정 중이면 다음 요청은 새 입력을 고정하지 않는지 검증한다. */
@ExtendWith(MockitoExtension.class)
class RedrawDrawingPreparationServiceTest {

    @Mock DrawingRepository drawingRepository;
    @Mock EventDrawingQueryService eventDrawingQueryService;
    @Mock SnapshotIntegrityService snapshotIntegrityService;
    @Mock DrawingSeedService drawingSeedService;
    @Mock RedrawExclusionRepository redrawExclusionRepository;
    @Mock RedrawDrawingQueryRepository redrawQueryRepository;
    @Mock DrawAttemptHistoryRepository attemptRepository;
    @Mock DrawInputHashGenerator inputHashGenerator;
    @Mock DrawInputV2HashGenerator inputV2HashGenerator;
    @Mock Drawing initial;
    @Mock Drawing runningRedraw;
    @Mock Drawing failedRedraw;
    @Mock DrawAttemptHistory failedAttempt;

    private RedrawDrawingPreparationService service;

    @BeforeEach
    void setUp() {
        service = new RedrawDrawingPreparationService(
                drawingRepository,
                eventDrawingQueryService,
                snapshotIntegrityService,
                drawingSeedService,
                redrawExclusionRepository,
                redrawQueryRepository,
                attemptRepository,
                inputHashGenerator,
                inputV2HashGenerator,
                Clock.fixed(Instant.parse("2026-09-22T00:00:00Z"), ZoneOffset.UTC)
        );
        when(drawingRepository.findById(20L)).thenReturn(Optional.of(initial));
        when(initial.getDrawType()).thenReturn(DrawingType.INITIAL);
        when(initial.getStatus()).thenReturn(DrawingStatus.COMPLETED);
        when(initial.getEventId()).thenReturn(10L);
        when(drawingRepository.findByRedrawRequestId(30L)).thenReturn(Optional.empty());
    }

    @Test
    void 같은_Event의_RUNNING_REDRAW가_있으면_후속_요청은_입력을_고정하지_않는다() {
        when(drawingRepository.findAllRedrawByEventIdAndStatusInForUpdate(
                10L, List.of(DrawingStatus.RUNNING, DrawingStatus.FAILED)))
                .thenReturn(List.of(runningRedraw));
        when(runningRedraw.getStatus()).thenReturn(DrawingStatus.RUNNING);

        assertConcurrentCommand();

        verifyNoInteractions(attemptRepository);
    }

    @Test
    void Retry_가능한_FAILED_REDRAW가_있으면_후속_요청은_입력을_고정하지_않는다() {
        when(drawingRepository.findAllRedrawByEventIdAndStatusInForUpdate(
                10L, List.of(DrawingStatus.RUNNING, DrawingStatus.FAILED)))
                .thenReturn(List.of(failedRedraw));
        when(failedRedraw.getStatus()).thenReturn(DrawingStatus.FAILED);
        when(failedRedraw.getId()).thenReturn(40L);
        when(attemptRepository.findFirstByDrawingIdOrderByAttemptNoDesc(40L))
                .thenReturn(Optional.of(failedAttempt));
        when(failedAttempt.isRetryable()).thenReturn(true);

        assertConcurrentCommand();
    }

    private void assertConcurrentCommand() {

        assertThatThrownBy(() -> service.prepare(30L, 1L, 20L, 1))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(DrawingErrorCode.CONCURRENT_COMMAND);

        verify(eventDrawingQueryService).getDrawingSourceForUpdate(10L);
        verifyNoInteractions(snapshotIntegrityService, drawingSeedService, redrawExclusionRepository,
                redrawQueryRepository, inputHashGenerator, inputV2HashGenerator);
    }
}
