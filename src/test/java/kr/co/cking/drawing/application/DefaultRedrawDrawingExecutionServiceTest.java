package kr.co.cking.drawing.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
import kr.co.cking.drawing.domain.RedrawExclusion;
import kr.co.cking.drawing.domain.RedrawExclusionReason;
import kr.co.cking.drawing.domain.engine.DrawInput;
import kr.co.cking.drawing.domain.engine.DrawOutput;
import kr.co.cking.drawing.domain.engine.DrawWinner;
import kr.co.cking.drawing.domain.engine.DrawingAlgorithmVersion;
import kr.co.cking.drawing.domain.engine.DrawingEngine;
import kr.co.cking.drawing.domain.hash.DrawInputHashGenerator;
import kr.co.cking.drawing.domain.hash.DrawInputV2HashGenerator;
import kr.co.cking.drawing.domain.hash.DrawResultHashGenerator;
import kr.co.cking.drawing.domain.hash.DrawResultV2HashGenerator;
import kr.co.cking.drawing.domain.prize.AllocatedPrize;
import kr.co.cking.drawing.domain.prize.PrizeAllocationAlgorithmVersion;
import kr.co.cking.drawing.domain.prize.PrizeAllocationEngine;
import kr.co.cking.drawing.domain.prize.PrizeAllocationInput;
import kr.co.cking.drawing.domain.prize.PrizeAllocationOutput;
import kr.co.cking.drawing.repository.DrawingRepository;
import kr.co.cking.drawing.repository.RedrawExclusionRepository;
import kr.co.cking.drawing.repository.RedrawExclusionSource;
import kr.co.cking.event.application.EventDrawingQueryService;
import kr.co.cking.event.application.dto.EventDrawingSource;
import kr.co.cking.event.domain.EventStatus;
import kr.co.cking.snapshot.application.SnapshotIntegrityService;
import kr.co.cking.snapshot.application.VerifiedSnapshot;
import kr.co.cking.snapshot.application.VerifiedSnapshotTestFactory;
import kr.co.cking.snapshot.domain.CandidateValue;
import kr.co.cking.snapshot.domain.PrizeValue;
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
    @Mock private EventDrawingQueryService eventDrawingQueryService;
    @Mock private SnapshotIntegrityService snapshotIntegrityService;
    @Mock private DrawingSeedService drawingSeedService;
    @Mock private DrawingEngine drawingEngine;
    @Mock private WinnerRepository winnerRepository;
    @Mock private WinnerManagementRepository winnerManagementRepository;
    @Mock private RedrawExclusionRepository redrawExclusionRepository;
    @Mock private PrizeAllocationEngine prizeAllocationEngine;

    private DefaultRedrawDrawingExecutionService service;

    @BeforeEach
    void setUp() {
        service = new DefaultRedrawDrawingExecutionService(
                drawingRepository, eventDrawingQueryService, snapshotIntegrityService, drawingSeedService, drawingEngine,
                new DrawInputHashGenerator(), new DrawResultHashGenerator(), winnerRepository,
                winnerManagementRepository, redrawExclusionRepository,
                Clock.fixed(Instant.parse("2026-09-22T00:00:00Z"), ZoneOffset.UTC)
        );
    }

    /** 상품 Snapshot REDRAW는 V2 Hash와 배정 상품을 Winner에 함께 저장한다. */
    @Test
    @SuppressWarnings("unchecked")
    void 상품_Snapshot_REDRAW는_V2_Hash와_Winner_상품_필드를_저장한다() {
        PrizeValue prize = new PrizeValue(501L, "FIRST", "1등 상품", 1, 100L, 1);
        VerifiedSnapshot snapshot = VerifiedSnapshotTestFactory.create(50L, 10L, 1, "WEIGHTED", "WEIGHTED_V1",
                List.of(new CandidateValue(101L, 1L), new CandidateValue(102L, 2L)), List.of(prize));
        Drawing initial = completedInitial(snapshot);
        DefaultRedrawDrawingExecutionService productService = new DefaultRedrawDrawingExecutionService(
                drawingRepository, eventDrawingQueryService, snapshotIntegrityService, drawingSeedService, drawingEngine,
                new DrawInputHashGenerator(), new DrawResultHashGenerator(), new DrawInputV2HashGenerator(),
                new DrawResultV2HashGenerator(), prizeAllocationEngine, winnerRepository,
                winnerManagementRepository, redrawExclusionRepository,
                Clock.fixed(Instant.parse("2026-09-22T00:00:00Z"), ZoneOffset.UTC)
        );
        when(drawingRepository.findById(INITIAL_DRAWING_ID)).thenReturn(Optional.of(initial));
        when(drawingRepository.findByRedrawRequestId(REQUEST_ID)).thenReturn(Optional.empty());
        when(eventDrawingQueryService.getDrawingSourceForUpdate(10L))
                .thenReturn(new EventDrawingSource(10L, EventStatus.PUBLISHED, null));
        when(snapshotIntegrityService.verifyForDrawing(10L)).thenReturn(snapshot);
        when(winnerRepository.findRedrawExclusionSourcesByEventId(10L))
                .thenReturn(List.of(new RedrawExclusionSource(101L, WinnerManagementStatus.SELECTED)));
        when(drawingSeedService.createForRedraw(40L)).thenReturn(new PersistedDrawingSeed(
                41L, kr.co.cking.drawing.domain.seed.DrawingSeed.from("01".repeat(32))));
        when(drawingRepository.findTopByEventIdOrderByDrawNoDesc(10L)).thenReturn(Optional.of(initial));
        when(drawingRepository.saveAndFlush(any(Drawing.class))).thenAnswer(invocation -> {
            Drawing redraw = invocation.getArgument(0);
            ReflectionTestUtils.setField(redraw, "id", REDRAW_DRAWING_ID);
            return redraw;
        });
        DrawOutput output = new DrawOutput(DrawingAlgorithmVersion.WEIGHTED_V1,
                List.of(new DrawWinner(102L, 1, 2L)));
        when(drawingEngine.draw(any(DrawInput.class))).thenReturn(output);
        when(prizeAllocationEngine.allocate(any(PrizeAllocationInput.class))).thenReturn(new PrizeAllocationOutput(
                PrizeAllocationAlgorithmVersion.PRIZE_WEIGHTED_V1,
                List.of(new AllocatedPrize(102L, 1, prize))
        ));
        when(winnerRepository.saveAllAndFlush(any())).thenAnswer(invocation -> {
            List<Winner> winners = invocation.getArgument(0);
            ReflectionTestUtils.setField(winners.getFirst(), "id", 50L);
            return winners;
        });

        productService.execute(REQUEST_ID, ADMIN_ID, INITIAL_DRAWING_ID, 1);

        ArgumentCaptor<PrizeAllocationInput> allocationInput = ArgumentCaptor.forClass(PrizeAllocationInput.class);
        verify(prizeAllocationEngine).allocate(allocationInput.capture());
        assertThat(allocationInput.getValue().prizes()).containsExactly(prize);
        ArgumentCaptor<List<Winner>> winners = ArgumentCaptor.forClass(List.class);
        verify(winnerRepository).saveAllAndFlush(winners.capture());
        Winner savedWinner = winners.getValue().getFirst();
        assertThat(savedWinner.getSnapshotId()).isEqualTo(snapshot.snapshotId());
        assertThat(savedWinner.getSnapshotPrizeId()).isEqualTo(prize.snapshotPrizeId());
        assertThat(savedWinner.getPrizeKey()).isEqualTo("FIRST");
        ArgumentCaptor<Drawing> redraw = ArgumentCaptor.forClass(Drawing.class);
        verify(drawingRepository).saveAndFlush(redraw.capture());
        assertThat(redraw.getValue().getInputPayload()).startsWith("CKING_DRAW_INPUT_V2\n");
        assertThat(redraw.getValue().getOutputPayload()).startsWith("CKING_DRAW_RESULT_V2\n");
    }

    /** 기존 Winner별 제외 사유를 REDRAW 입력·영속 행에 같은 순서로 확정한다. */
    @Test
    @SuppressWarnings("unchecked")
    void REDRAW_생성_트랜잭션에서_모든_제외_Member와_사유를_저장한다() {
        VerifiedSnapshot snapshot = snapshot();
        Drawing initial = completedInitial(snapshot);
        when(drawingRepository.findById(INITIAL_DRAWING_ID)).thenReturn(Optional.of(initial));
        when(drawingRepository.findByRedrawRequestId(REQUEST_ID)).thenReturn(Optional.empty());
        when(eventDrawingQueryService.getDrawingSourceForUpdate(10L))
                .thenReturn(new EventDrawingSource(10L, EventStatus.PUBLISHED, null));
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

    /** 완료되지 않은 원본 INITIAL Drawing은 Event 잠금과 시스템3 실행 전에 거부한다. */
    @Test
    void 완료되지_않은_원본_INITIAL_Drawing은_INVALID_STATE다() {
        Drawing initial = Drawing.createInitial(DrawingSnapshotContract.from(snapshot()), 40L, ADMIN_ID);
        ReflectionTestUtils.setField(initial, "id", INITIAL_DRAWING_ID);
        when(drawingRepository.findById(INITIAL_DRAWING_ID)).thenReturn(Optional.of(initial));

        assertThatThrownBy(() -> service.execute(REQUEST_ID, ADMIN_ID, INITIAL_DRAWING_ID, 1))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(DrawingErrorCode.INVALID_STATE);

        verifyNoInteractions(eventDrawingQueryService, snapshotIntegrityService, drawingSeedService, drawingEngine);
    }

    /** 실제 Event 잠금은 최신 회차 조회와 REDRAW Drawing 생성보다 먼저 수행된다. */
    @Test
    void Event_잠금_후_최신_회차를_계산한다() {
        VerifiedSnapshot snapshot = snapshot();
        Drawing initial = completedInitial(snapshot);
        when(drawingRepository.findById(INITIAL_DRAWING_ID)).thenReturn(Optional.of(initial));
        when(drawingRepository.findByRedrawRequestId(REQUEST_ID)).thenReturn(Optional.empty());
        when(eventDrawingQueryService.getDrawingSourceForUpdate(10L))
                .thenReturn(new EventDrawingSource(10L, EventStatus.PUBLISHED, null));
        when(snapshotIntegrityService.verifyForDrawing(10L)).thenReturn(snapshot);
        when(winnerRepository.findRedrawExclusionSourcesByEventId(10L)).thenReturn(List.of());
        when(drawingSeedService.createForRedraw(40L))
                .thenReturn(new PersistedDrawingSeed(41L, kr.co.cking.drawing.domain.seed.DrawingSeed.from("01".repeat(32))));
        when(drawingRepository.findTopByEventIdOrderByDrawNoDesc(10L)).thenReturn(Optional.of(initial));
        when(drawingRepository.saveAndFlush(any(Drawing.class))).thenAnswer(invocation -> {
            Drawing redraw = invocation.getArgument(0);
            ReflectionTestUtils.setField(redraw, "id", REDRAW_DRAWING_ID);
            return redraw;
        });
        when(drawingEngine.draw(any(DrawInput.class))).thenReturn(new DrawOutput(
                DrawingAlgorithmVersion.WEIGHTED_V1, List.of(new DrawWinner(101L, 1, 1L))));
        when(winnerRepository.saveAllAndFlush(any())).thenAnswer(invocation -> {
            List<Winner> winners = invocation.getArgument(0);
            ReflectionTestUtils.setField(winners.getFirst(), "id", 50L);
            return winners;
        });

        service.execute(REQUEST_ID, ADMIN_ID, INITIAL_DRAWING_ID, 1);

        org.mockito.InOrder order = org.mockito.Mockito.inOrder(eventDrawingQueryService, drawingRepository);
        order.verify(eventDrawingQueryService).getDrawingSourceForUpdate(10L);
        order.verify(drawingRepository).findTopByEventIdOrderByDrawNoDesc(10L);
        order.verify(drawingRepository).saveAndFlush(any(Drawing.class));
    }

    /** 두 번째 REDRAW는 INITIAL이 아니라 직전 REDRAW의 Seed를 기준으로 새 Seed를 생성한다. */
    @Test
    void 두_번째_REDRAW는_직전_REDRAW의_Seed를_사용한다() {
        VerifiedSnapshot snapshot = snapshot();
        Drawing initial = completedInitial(snapshot);
        Drawing previousRedraw = Drawing.createRedraw(initial, 1, 11L, 41L, 1, ADMIN_ID);
        ReflectionTestUtils.setField(previousRedraw, "id", REDRAW_DRAWING_ID);
        when(drawingRepository.findById(INITIAL_DRAWING_ID)).thenReturn(Optional.of(initial));
        when(drawingRepository.findByRedrawRequestId(REQUEST_ID)).thenReturn(Optional.empty());
        when(eventDrawingQueryService.getDrawingSourceForUpdate(10L))
                .thenReturn(new EventDrawingSource(10L, EventStatus.PUBLISHED, null));
        when(snapshotIntegrityService.verifyForDrawing(10L)).thenReturn(snapshot);
        when(winnerRepository.findRedrawExclusionSourcesByEventId(10L)).thenReturn(List.of());
        when(drawingRepository.findTopByEventIdOrderByDrawNoDesc(10L)).thenReturn(Optional.of(previousRedraw));
        when(drawingSeedService.createForRedraw(41L))
                .thenReturn(new PersistedDrawingSeed(42L, kr.co.cking.drawing.domain.seed.DrawingSeed.from("02".repeat(32))));
        when(drawingRepository.saveAndFlush(any(Drawing.class))).thenAnswer(invocation -> {
            Drawing redraw = invocation.getArgument(0);
            ReflectionTestUtils.setField(redraw, "id", 31L);
            return redraw;
        });
        when(drawingEngine.draw(any(DrawInput.class))).thenReturn(new DrawOutput(
                DrawingAlgorithmVersion.WEIGHTED_V1, List.of(new DrawWinner(101L, 1, 1L))));
        when(winnerRepository.saveAllAndFlush(any())).thenAnswer(invocation -> {
            List<Winner> winners = invocation.getArgument(0);
            ReflectionTestUtils.setField(winners.getFirst(), "id", 51L);
            return winners;
        });

        service.execute(REQUEST_ID, ADMIN_ID, INITIAL_DRAWING_ID, 1);

        verify(drawingSeedService).createForRedraw(41L);
        ArgumentCaptor<Drawing> redraw = ArgumentCaptor.forClass(Drawing.class);
        verify(drawingRepository).saveAndFlush(redraw.capture());
        assertThat(redraw.getValue().getDrawNo()).isEqualTo(2);
    }

    private Drawing completedInitial(VerifiedSnapshot snapshot) {
        Drawing initial = Drawing.createInitial(DrawingSnapshotContract.from(snapshot), 40L, ADMIN_ID);
        ReflectionTestUtils.setField(initial, "id", INITIAL_DRAWING_ID);
        ReflectionTestUtils.setField(initial, "status", DrawingStatus.COMPLETED);
        return initial;
    }

    private VerifiedSnapshot snapshot() {
        return VerifiedSnapshotTestFactory.create(50L, 10L, 1, "WEIGHTED", "WEIGHTED_V1", List.of(
                new CandidateValue(101L, 1L), new CandidateValue(102L, 1L),
                new CandidateValue(103L, 1L), new CandidateValue(104L, 1L)
        ));
    }
}
