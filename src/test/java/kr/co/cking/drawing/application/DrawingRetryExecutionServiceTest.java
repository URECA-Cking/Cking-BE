package kr.co.cking.drawing.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import kr.co.cking.drawing.domain.DrawAttemptHistory;
import kr.co.cking.drawing.domain.Drawing;
import kr.co.cking.drawing.domain.DrawingErrorCode;
import kr.co.cking.drawing.domain.DrawingFailureStage;
import kr.co.cking.drawing.domain.DrawingStatus;
import kr.co.cking.drawing.domain.DrawingType;
import kr.co.cking.drawing.domain.engine.DrawingEngine;
import kr.co.cking.drawing.domain.hash.DrawInputHashGenerator;
import kr.co.cking.drawing.domain.hash.DrawInputV2HashGenerator;
import kr.co.cking.drawing.domain.hash.DrawResultHashGenerator;
import kr.co.cking.drawing.domain.hash.DrawResultV2HashGenerator;
import kr.co.cking.drawing.domain.prize.PrizeAllocationEngine;
import kr.co.cking.drawing.domain.seed.DrawingSeed;
import kr.co.cking.drawing.repository.DrawAttemptHistoryRepository;
import kr.co.cking.drawing.repository.DrawingExclusionQueryRepository;
import kr.co.cking.drawing.repository.DrawingRepository;
import kr.co.cking.event.application.service.EventCommandService;
import kr.co.cking.redraw.repository.RedrawRequestRepository;
import kr.co.cking.snapshot.application.SnapshotIntegrityService;
import kr.co.cking.snapshot.application.VerifiedSnapshot;
import kr.co.cking.snapshot.application.VerifiedSnapshotTestFactory;
import kr.co.cking.snapshot.domain.CandidateValue;
import kr.co.cking.winner.repository.WinnerManagementRepository;
import kr.co.cking.winner.repository.WinnerRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DrawingRetryExecutionServiceTest {

    @Mock DrawingRepository drawingRepository;
    @Mock DrawAttemptHistoryRepository attemptRepository;
    @Mock SnapshotIntegrityService snapshotIntegrityService;
    @Mock DrawingSeedService drawingSeedService;
    @Mock DrawingExclusionQueryRepository exclusionRepository;
    @Mock DrawingEngine drawingEngine;
    @Mock PrizeAllocationEngine prizeAllocationEngine;
    @Mock WinnerRepository winnerRepository;
    @Mock WinnerManagementRepository winnerManagementRepository;
    @Mock EventCommandService eventCommandService;
    @Mock RedrawRequestRepository redrawRequestRepository;
    @Mock Drawing drawing;

    @Test
    void 보존된_Input_Hash가_다르면_엔진_호출_전에_비재시도_실패한다() {
        Instant now = Instant.parse("2026-09-22T00:00:00Z");
        DrawAttemptHistory attempt = DrawAttemptHistory.started(10L, 2, 1L, now);
        VerifiedSnapshot snapshot = VerifiedSnapshotTestFactory.create(
                20L, 30L, 1, "WEIGHTED", "WEIGHTED_V1", List.of(new CandidateValue(40L, 3L)));
        when(drawingRepository.findByIdForRetry(10L)).thenReturn(Optional.of(drawing));
        when(attemptRepository.findByDrawingIdAndAttemptNo(10L, 2)).thenReturn(Optional.of(attempt));
        when(drawing.getStatus()).thenReturn(DrawingStatus.RUNNING);
        when(drawing.getId()).thenReturn(10L);
        when(drawing.getAttemptCount()).thenReturn(2);
        when(drawing.getEventId()).thenReturn(30L);
        when(drawing.getSnapshotId()).thenReturn(20L);
        when(drawing.getSeedId()).thenReturn(50L);
        when(drawing.getAlgorithmVersion()).thenReturn("WEIGHTED_V1");
        when(drawing.getPrizeAlgorithmVersion()).thenReturn("PRIZE_WEIGHTED_V1");
        when(drawing.getDrawType()).thenReturn(DrawingType.INITIAL);
        when(drawing.getWinnerCount()).thenReturn(1);
        when(drawing.getInputHash()).thenReturn("f".repeat(64));
        when(drawing.getInputPayload()).thenReturn("tampered");
        when(snapshotIntegrityService.verifyForReplay(20L)).thenReturn(snapshot);
        when(drawingSeedService.reuseForRetry(50L)).thenReturn(
                new PersistedDrawingSeed(50L, DrawingSeed.from("01".repeat(32))));
        when(exclusionRepository.findMemberIdsByDrawingId(10L)).thenReturn(List.of());
        DrawingRetryExecutionService service = new DrawingRetryExecutionService(
                drawingRepository, attemptRepository, snapshotIntegrityService, drawingSeedService,
                exclusionRepository, drawingEngine, prizeAllocationEngine, new DrawInputHashGenerator(),
                new DrawInputV2HashGenerator(), new DrawResultHashGenerator(), new DrawResultV2HashGenerator(),
                winnerRepository, winnerManagementRepository, eventCommandService, redrawRequestRepository,
                Clock.fixed(now, ZoneOffset.UTC));

        assertThatThrownBy(() -> service.execute(new DrawingRetryRequest(10L, 2)))
                .isInstanceOfSatisfying(DrawingExecutionFailure.class, failure -> {
                    assertThat(failure.stage()).isEqualTo(DrawingFailureStage.INPUT_VERIFICATION);
                    assertThat(failure.failureCode())
                            .as("original=%s", failure.original())
                            .isEqualTo(DrawingErrorCode.NON_RETRYABLE_FAILURE.code());
                });
        verifyNoInteractions(drawingEngine);
    }
}
