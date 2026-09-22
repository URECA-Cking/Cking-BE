package kr.co.cking.drawing.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.drawing.domain.DrawAttemptHistory;
import kr.co.cking.drawing.domain.Drawing;
import kr.co.cking.drawing.domain.DrawingErrorCode;
import kr.co.cking.drawing.domain.DrawingFailureStage;
import kr.co.cking.drawing.domain.DrawingStatus;
import kr.co.cking.drawing.repository.DrawAttemptHistoryRepository;
import kr.co.cking.drawing.repository.DrawingRepository;
import kr.co.cking.member.application.MemberQueryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DrawingRetryPreparationServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-22T00:00:00Z");

    @Mock MemberQueryService memberQueryService;
    @Mock DrawingRepository drawingRepository;
    @Mock DrawAttemptHistoryRepository attemptRepository;

    private DrawingRetryPreparationService service;

    @BeforeEach
    void setUp() {
        service = new DrawingRetryPreparationService(memberQueryService, drawingRepository, attemptRepository,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void FAILED_Drawing을_잠근_뒤_RUNNING과_새_Attempt로_전이한다() {
        Drawing drawing = failedDrawing(1);
        when(drawingRepository.findByIdForRetry(10L)).thenReturn(Optional.of(drawing));
        when(attemptRepository.findFirstByDrawingIdOrderByAttemptNoDesc(10L)).thenReturn(Optional.empty());

        DrawingRetryRequest result = service.prepare(10L, 1L);

        assertThat(result).isEqualTo(new DrawingRetryRequest(10L, 2));
        assertThat(drawing.getStatus()).isEqualTo(DrawingStatus.RUNNING);
        verify(memberQueryService).validateAdmin(1L);
        verify(attemptRepository).saveAndFlush(org.mockito.ArgumentMatchers.argThat(attempt ->
                attempt.getDrawingId().equals(10L) && attempt.getAttemptNo() == 2));
    }

    @Test
    void 입력_검증_실패_Drawing은_엔진_실행_전에_차단한다() {
        Drawing drawing = failedDrawing(1);
        DrawAttemptHistory history = DrawAttemptHistory.started(10L, 1, 1L, NOW.minusSeconds(10));
        history.fail(DrawingFailureStage.INPUT_VERIFICATION, "SNAPSHOT_HASH_MISMATCH", "불일치", NOW);
        when(drawingRepository.findByIdForRetry(10L)).thenReturn(Optional.of(drawing));
        when(attemptRepository.findFirstByDrawingIdOrderByAttemptNoDesc(10L)).thenReturn(Optional.of(history));

        assertThatThrownBy(() -> service.prepare(10L, 1L))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", DrawingErrorCode.NON_RETRYABLE_FAILURE);
        assertThat(drawing.getStatus()).isEqualTo(DrawingStatus.FAILED);
    }

    @Test
    void 입력_검증_단계의_일시적인_시스템_오류는_Retry를_시작한다() {
        Drawing drawing = failedDrawing(1);
        DrawAttemptHistory history = DrawAttemptHistory.started(10L, 1, 1L, NOW.minusSeconds(10));
        history.fail(DrawingFailureStage.INPUT_VERIFICATION, "SYSTEM_ERROR", "DB timeout", NOW);
        when(drawingRepository.findByIdForRetry(10L)).thenReturn(Optional.of(drawing));
        when(attemptRepository.findFirstByDrawingIdOrderByAttemptNoDesc(10L)).thenReturn(Optional.of(history));

        DrawingRetryRequest result = service.prepare(10L, 1L);

        assertThat(result).isEqualTo(new DrawingRetryRequest(10L, 2));
        assertThat(drawing.getStatus()).isEqualTo(DrawingStatus.RUNNING);
    }

    @Test
    void RUNNING_Drawing의_동시_Retry는_거부한다() {
        Drawing drawing = failedDrawing(1);
        drawing.retry(NOW);
        when(drawingRepository.findByIdForRetry(10L)).thenReturn(Optional.of(drawing));

        assertThatThrownBy(() -> service.prepare(10L, 2L))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", DrawingErrorCode.CONCURRENT_COMMAND);
    }

    private Drawing failedDrawing(int attemptCount) {
        Drawing drawing = org.mockito.Mockito.mock(Drawing.class);
        when(drawing.getId()).thenReturn(10L);
        when(drawing.getStatus()).thenReturn(DrawingStatus.FAILED);
        when(drawing.getAttemptCount()).thenReturn(attemptCount + 1);
        org.mockito.Mockito.doAnswer(invocation -> {
            when(drawing.getStatus()).thenReturn(DrawingStatus.RUNNING);
            return null;
        }).when(drawing).retry(org.mockito.ArgumentMatchers.any());
        return drawing;
    }
}
