package kr.co.cking.drawing.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.drawing.domain.Drawing;
import kr.co.cking.drawing.domain.DrawingErrorCode;
import kr.co.cking.drawing.domain.DrawingSnapshotContract;
import kr.co.cking.drawing.domain.DrawingStatus;
import kr.co.cking.drawing.domain.engine.DrawInput;
import kr.co.cking.drawing.domain.engine.DrawOutput;
import kr.co.cking.drawing.domain.engine.DrawWinner;
import kr.co.cking.drawing.domain.engine.DrawingAlgorithmVersion;
import kr.co.cking.drawing.domain.engine.DrawingEngine;
import kr.co.cking.drawing.domain.hash.DrawInputHashGenerator;
import kr.co.cking.drawing.domain.hash.DrawResultHashGenerator;
import kr.co.cking.drawing.domain.seed.DrawingSeed;
import kr.co.cking.drawing.repository.DrawingRepository;
import kr.co.cking.event.application.EventDrawingQueryService;
import kr.co.cking.event.application.dto.EventDrawingSource;
import kr.co.cking.event.application.service.EventCommandService;
import kr.co.cking.event.domain.EventErrorCode;
import kr.co.cking.event.domain.EventStatus;
import kr.co.cking.member.application.MemberQueryService;
import kr.co.cking.snapshot.application.SnapshotIntegrityService;
import kr.co.cking.snapshot.application.VerifiedSnapshot;
import kr.co.cking.snapshot.application.VerifiedSnapshotTestFactory;
import kr.co.cking.snapshot.domain.CandidateValue;
import kr.co.cking.winner.domain.Winner;
import kr.co.cking.winner.domain.WinnerManagement;
import kr.co.cking.winner.repository.WinnerManagementRepository;
import kr.co.cking.winner.repository.WinnerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class InitialDrawingExecutionServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-18T00:00:00Z");

    @Mock private MemberQueryService memberQueryService;
    @Mock private EventDrawingQueryService eventDrawingQueryService;
    @Mock private SnapshotIntegrityService snapshotIntegrityService;
    @Mock private DrawingSeedService drawingSeedService;
    @Mock private DrawingRepository drawingRepository;
    @Mock private WinnerRepository winnerRepository;
    @Mock private WinnerManagementRepository winnerManagementRepository;
    @Mock private DrawingEngine drawingEngine;
    @Mock private EventCommandService eventCommandService;

    private InitialDrawingExecutionService service;

    @BeforeEach
    void setUp() {
        service = new InitialDrawingExecutionService(
                memberQueryService,
                eventDrawingQueryService,
                snapshotIntegrityService,
                drawingSeedService,
                drawingRepository,
                winnerRepository,
                winnerManagementRepository,
                drawingEngine,
                new DrawInputHashGenerator(),
                new DrawResultHashGenerator(),
                eventCommandService,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    @Test
    void 검증된_Snapshot으로_추첨하고_Winner와_운영상태를_저장한_뒤_Event를_전이한다() {
        VerifiedSnapshot snapshot = snapshot();
        PersistedDrawingSeed seed = new PersistedDrawingSeed(
                30L, DrawingSeed.from("01".repeat(32)));
        when(eventDrawingQueryService.getDrawingSourceForUpdate(10L))
                .thenReturn(source(EventStatus.CLOSED));
        when(drawingRepository.findByEventIdAndDrawNoForUpdate(10L, 0)).thenReturn(Optional.empty());
        when(snapshotIntegrityService.verifyForDrawing(10L)).thenReturn(snapshot);
        when(drawingSeedService.createForInitial()).thenReturn(seed);
        when(drawingRepository.saveAndFlush(any(Drawing.class))).thenAnswer(invocation -> {
            Drawing drawing = invocation.getArgument(0);
            ReflectionTestUtils.setField(drawing, "id", 40L);
            return drawing;
        });
        when(drawingEngine.draw(any(DrawInput.class))).thenReturn(output());
        when(winnerRepository.existsByEventIdAndMemberId(any(), any())).thenReturn(false);
        when(winnerRepository.saveAllAndFlush(any())).thenAnswer(invocation -> {
            List<Winner> winners = invocation.getArgument(0);
            assertThat(winners).extracting(Winner::getRankInDrawing).containsExactly(1, 2);
            for (int index = 0; index < winners.size(); index++) {
                ReflectionTestUtils.setField(winners.get(index), "id", 100L + index);
            }
            return winners;
        });

        InitialDrawingResult result = service.execute(1L, 10L);

        assertThat(result).isEqualTo(new InitialDrawingResult(40L, 10L, DrawingStatus.COMPLETED, 2));
        verify(memberQueryService).validateAdmin(1L);
        verify(drawingEngine).draw(any(DrawInput.class));
        verify(winnerRepository).saveAllAndFlush(any());
        verify(winnerManagementRepository).saveAllAndFlush(any());
        verify(eventCommandService).completeDrawing(10L);
    }

    @Test
    void 완료된_INITIAL_Drawing은_다시_실행하지_않고_기존_결과를_반환한다() {
        Drawing completed = drawing(DrawingStatus.COMPLETED);
        when(eventDrawingQueryService.getDrawingSourceForUpdate(10L))
                .thenReturn(source(EventStatus.DRAW_COMPLETED));
        when(drawingRepository.findByEventIdAndDrawNoForUpdate(10L, 0)).thenReturn(Optional.of(completed));

        InitialDrawingResult result = service.execute(1L, 10L);

        assertThat(result.drawingId()).isEqualTo(40L);
        assertThat(result.status()).isEqualTo(DrawingStatus.COMPLETED);
        verify(snapshotIntegrityService, never()).verifyForDrawing(any());
        verify(drawingEngine, never()).draw(any());
    }

    @Test
    void READY나_RUNNING_INITIAL_Drawing은_동시명령으로_거부한다() {
        when(eventDrawingQueryService.getDrawingSourceForUpdate(10L))
                .thenReturn(source(EventStatus.CLOSED));
        when(drawingRepository.findByEventIdAndDrawNoForUpdate(10L, 0))
                .thenReturn(Optional.of(drawing(DrawingStatus.RUNNING)));

        assertThatThrownBy(() -> service.execute(1L, 10L))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", DrawingErrorCode.CONCURRENT_COMMAND);
    }

    @Test
    void FAILED_INITIAL_Drawing은_신규생성하지_않고_Retry를_기다린다() {
        when(eventDrawingQueryService.getDrawingSourceForUpdate(10L))
                .thenReturn(source(EventStatus.CLOSED));
        when(drawingRepository.findByEventIdAndDrawNoForUpdate(10L, 0))
                .thenReturn(Optional.of(drawing(DrawingStatus.FAILED)));

        assertThatThrownBy(() -> service.execute(1L, 10L))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", DrawingErrorCode.INVALID_STATE);
        verify(drawingSeedService, never()).createForInitial();
    }

    @Test
    void 삭제됐거나_CLOSED가_아닌_Event은_실행하지_않는다() {
        when(eventDrawingQueryService.getDrawingSourceForUpdate(10L))
                .thenReturn(source(EventStatus.OPEN));
        when(drawingRepository.findByEventIdAndDrawNoForUpdate(10L, 0)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.execute(1L, 10L))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", EventErrorCode.INVALID_STATE);
        verify(snapshotIntegrityService, never()).verifyForDrawing(any());
    }

    @Test
    void 삭제된_CLOSED_Event도_실행하지_않는다() {
        when(eventDrawingQueryService.getDrawingSourceForUpdate(10L))
                .thenReturn(new EventDrawingSource(10L, EventStatus.CLOSED, NOW));
        when(drawingRepository.findByEventIdAndDrawNoForUpdate(10L, 0)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.execute(1L, 10L))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", EventErrorCode.INVALID_STATE);
        verify(snapshotIntegrityService, never()).verifyForDrawing(any());
    }

    private EventDrawingSource source(EventStatus status) {
        return new EventDrawingSource(10L, status, null);
    }

    private VerifiedSnapshot snapshot() {
        return VerifiedSnapshotTestFactory.create(
                20L,
                10L,
                2,
                "WEIGHTED",
                "WEIGHTED_V1",
                List.of(new CandidateValue(101L, 3L), new CandidateValue(102L, 7L))
        );
    }

    private DrawOutput output() {
        return new DrawOutput(
                DrawingAlgorithmVersion.WEIGHTED_V1,
                List.of(new DrawWinner(102L, 2, 7L), new DrawWinner(101L, 1, 3L))
        );
    }

    private Drawing drawing(DrawingStatus status) {
        Drawing drawing = Drawing.createInitial(
                DrawingSnapshotContract.from(snapshot()),
                30L,
                1L
        );
        ReflectionTestUtils.setField(drawing, "id", 40L);
        ReflectionTestUtils.setField(drawing, "status", status);
        return drawing;
    }
}
