package kr.co.cking.drawing.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import kr.co.cking.drawing.domain.Drawing;
import kr.co.cking.drawing.domain.DrawingSnapshotContract;
import kr.co.cking.drawing.domain.RedrawExclusion;
import kr.co.cking.drawing.domain.RedrawExclusionReason;
import kr.co.cking.drawing.domain.engine.DrawInput;
import kr.co.cking.drawing.domain.engine.DrawOutput;
import kr.co.cking.drawing.domain.engine.DrawWinner;
import kr.co.cking.drawing.domain.engine.DrawingAlgorithmVersion;
import kr.co.cking.drawing.domain.engine.DrawingEngine;
import kr.co.cking.drawing.domain.hash.DrawInputHashGenerator;
import kr.co.cking.drawing.domain.hash.DrawResultHashGenerator;
import kr.co.cking.drawing.repository.DrawingRepository;
import kr.co.cking.drawing.repository.RedrawExclusionRepository;
import kr.co.cking.drawing.repository.RedrawExclusionSource;
import kr.co.cking.snapshot.application.SnapshotIntegrityService;
import kr.co.cking.snapshot.application.VerifiedSnapshot;
import kr.co.cking.snapshot.application.VerifiedSnapshotTestFactory;
import kr.co.cking.snapshot.domain.CandidateValue;
import kr.co.cking.winner.domain.Winner;
import kr.co.cking.winner.domain.WinnerManagementStatus;
import kr.co.cking.winner.repository.WinnerManagementRepository;
import kr.co.cking.winner.repository.WinnerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/** REDRAW Drawing 생성 시 Hash 입력의 제외 명단을 함께 보존하는지 검증한다. */
@ExtendWith(MockitoExtension.class)
class DefaultRedrawDrawingExecutionServiceTest {

    private static final long REQUEST_ID = 10L;
    private static final long ADMIN_ID = 1L;
    private static final long INITIAL_DRAWING_ID = 20L;
    private static final long REDRAW_DRAWING_ID = 30L;

    @Mock private DrawingRepository drawingRepository;
    @Mock private SnapshotIntegrityService snapshotIntegrityService;
    @Mock private DrawingSeedService drawingSeedService;
    @Mock private DrawingEngine drawingEngine;
    @Mock private WinnerRepository winnerRepository;
    @Mock private WinnerManagementRepository winnerManagementRepository;
    @Mock private RedrawExclusionRepository redrawExclusionRepository;

    private DefaultRedrawDrawingExecutionService service;

    @BeforeEach
    void setUp() {
        service = new DefaultRedrawDrawingExecutionService(
                drawingRepository, snapshotIntegrityService, drawingSeedService, drawingEngine,
                new DrawInputHashGenerator(), new DrawResultHashGenerator(), winnerRepository,
                winnerManagementRepository, redrawExclusionRepository,
                Clock.fixed(Instant.parse("2026-09-22T00:00:00Z"), ZoneOffset.UTC)
        );
    }

    /** 기존 Winner별 제외 사유를 REDRAW 입력·영속 행에 같은 순서로 확정한다. */
    @Test
    @SuppressWarnings("unchecked")
    void REDRAW_생성_트랜잭션에서_모든_제외_Member와_사유를_저장한다() {
        VerifiedSnapshot snapshot = snapshot();
        Drawing initial = Drawing.createInitial(DrawingSnapshotContract.from(snapshot), 40L, ADMIN_ID);
        ReflectionTestUtils.setField(initial, "id", INITIAL_DRAWING_ID);
        when(drawingRepository.findById(INITIAL_DRAWING_ID)).thenReturn(Optional.of(initial));
        when(drawingRepository.findByRedrawRequestId(REQUEST_ID)).thenReturn(Optional.empty());
        when(snapshotIntegrityService.verifyForDrawing(10L)).thenReturn(snapshot);
        when(winnerRepository.findRedrawExclusionSourcesByEventId(10L)).thenReturn(List.of(
                new RedrawExclusionSource(101L, WinnerManagementStatus.SELECTED),
                new RedrawExclusionSource(103L, WinnerManagementStatus.DECLINED),
                new RedrawExclusionSource(104L, WinnerManagementStatus.DISQUALIFIED)
        ));
        when(drawingSeedService.createForRedraw(40L))
                .thenReturn(new PersistedDrawingSeed(41L, kr.co.cking.drawing.domain.seed.DrawingSeed.from("01".repeat(32))));
        when(drawingRepository.findTopByEventIdOrderByDrawNoDesc(10L)).thenReturn(Optional.of(initial));
        when(drawingRepository.saveAndFlush(any(Drawing.class))).thenAnswer(invocation -> {
            Drawing redraw = invocation.getArgument(0);
            ReflectionTestUtils.setField(redraw, "id", REDRAW_DRAWING_ID);
            return redraw;
        });
        when(drawingEngine.draw(any(DrawInput.class))).thenReturn(new DrawOutput(
                DrawingAlgorithmVersion.WEIGHTED_V1, List.of(new DrawWinner(102L, 1, 1L))
        ));
        when(winnerRepository.saveAllAndFlush(any())).thenAnswer(invocation -> {
            List<Winner> winners = invocation.getArgument(0);
            ReflectionTestUtils.setField(winners.getFirst(), "id", 50L);
            return winners;
        });

        RedrawDrawingExecutionResult result = service.execute(REQUEST_ID, ADMIN_ID, INITIAL_DRAWING_ID, 1);

        assertThat(result).isEqualTo(RedrawDrawingExecutionResult.executed(REDRAW_DRAWING_ID));
        ArgumentCaptor<List<RedrawExclusion>> exclusions = ArgumentCaptor.forClass(List.class);
        verify(redrawExclusionRepository).saveAll(exclusions.capture());
        assertThat(exclusions.getValue()).extracting(RedrawExclusion::getMemberId)
                .containsExactly(101L, 103L, 104L);
        assertThat(exclusions.getValue()).extracting(RedrawExclusion::getExclusionReason)
                .containsExactly(RedrawExclusionReason.ALREADY_WINNER, RedrawExclusionReason.DECLINED,
                        RedrawExclusionReason.DISQUALIFIED);
        ArgumentCaptor<DrawInput> input = ArgumentCaptor.forClass(DrawInput.class);
        verify(drawingEngine).draw(input.capture());
        assertThat(input.getValue().excludedMemberIds()).containsExactlyInAnyOrder(101L, 103L, 104L);
    }

    private VerifiedSnapshot snapshot() {
        return VerifiedSnapshotTestFactory.create(50L, 10L, 1, "WEIGHTED", "WEIGHTED_V1", List.of(
                new CandidateValue(101L, 1L), new CandidateValue(102L, 1L),
                new CandidateValue(103L, 1L), new CandidateValue(104L, 1L)
        ));
    }
}
