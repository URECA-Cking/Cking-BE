package kr.co.cking.drawing.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.LongStream;
import kr.co.cking.drawing.domain.Drawing;
import kr.co.cking.drawing.domain.DrawingStatus;
import kr.co.cking.drawing.domain.DrawingType;
import kr.co.cking.drawing.domain.DrawingVerificationHistory;
import kr.co.cking.drawing.domain.DrawingVerificationStatus;
import kr.co.cking.drawing.domain.engine.DrawInput;
import kr.co.cking.drawing.domain.engine.DrawOutput;
import kr.co.cking.drawing.domain.engine.DrawWinner;
import kr.co.cking.drawing.domain.engine.DrawingAlgorithmVersion;
import kr.co.cking.drawing.domain.engine.DrawingEngine;
import kr.co.cking.drawing.domain.hash.DrawInputHashGenerator;
import kr.co.cking.drawing.domain.hash.DrawInputV2HashGenerator;
import kr.co.cking.drawing.domain.hash.DrawResultHashGenerator;
import kr.co.cking.drawing.domain.hash.DrawResultV2HashGenerator;
import kr.co.cking.drawing.domain.hash.DrawingHash;
import kr.co.cking.drawing.domain.prize.WeightedPrizeV1AllocationEngine;
import kr.co.cking.drawing.domain.prize.AllocatedPrize;
import kr.co.cking.drawing.domain.prize.PrizeAllocationAlgorithmVersion;
import kr.co.cking.drawing.domain.prize.PrizeAllocationOutput;
import kr.co.cking.drawing.domain.seed.DrawingSeed;
import kr.co.cking.drawing.repository.DrawingExclusionQueryRepository;
import kr.co.cking.drawing.repository.DrawingRepository;
import kr.co.cking.drawing.repository.DrawingVerificationHistoryRepository;
import kr.co.cking.drawing.repository.RedrawDrawingQueryRepository;
import kr.co.cking.drawing.repository.RedrawVacancyPrizeSource;
import kr.co.cking.member.application.MemberQueryService;
import kr.co.cking.snapshot.application.SnapshotIntegrityService;
import kr.co.cking.snapshot.application.VerifiedSnapshot;
import kr.co.cking.snapshot.application.VerifiedSnapshotTestFactory;
import kr.co.cking.snapshot.domain.CandidateValue;
import kr.co.cking.snapshot.domain.PrizeValue;
import kr.co.cking.winner.domain.Winner;
import kr.co.cking.winner.repository.WinnerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DrawingVerificationServiceTest {

    private static final long ADMIN_ID = 1L;
    private static final long DRAWING_ID = 20L;
    private static final long EVENT_ID = 10L;
    private static final long SNAPSHOT_ID = 30L;
    private static final long SEED_ID = 40L;
    private static final String ORIGINAL_SEED = "01".repeat(32);
    private static final String REPLAY_SEED = "02".repeat(32);
    private static final Instant NOW = Instant.parse("2026-09-18T00:00:00Z");

    @Mock private MemberQueryService memberQueryService;
    @Mock private DrawingRepository drawingRepository;
    @Mock private WinnerRepository winnerRepository;
    @Mock private DrawingVerificationHistoryRepository historyRepository;
    @Mock private DrawingExclusionQueryRepository exclusionRepository;
    @Mock private RedrawDrawingQueryRepository redrawDrawingQueryRepository;
    @Mock private SnapshotIntegrityService snapshotIntegrityService;
    @Mock private DrawingSeedService drawingSeedService;
    @Mock private DrawingSeedPolicy drawingSeedPolicy;
    @Mock private DrawingEngine drawingEngine;

    private final DrawInputHashGenerator inputHashGenerator = new DrawInputHashGenerator();
    private final DrawResultHashGenerator resultHashGenerator = new DrawResultHashGenerator();
    private final DrawInputV2HashGenerator inputV2HashGenerator = new DrawInputV2HashGenerator();
    private final DrawResultV2HashGenerator resultV2HashGenerator = new DrawResultV2HashGenerator();
    private DrawingVerificationService service;

    @BeforeEach
    void setUp() {
        service = new DrawingVerificationService(
                memberQueryService,
                drawingRepository,
                winnerRepository,
                historyRepository,
                exclusionRepository,
                redrawDrawingQueryRepository,
                snapshotIntegrityService,
                drawingSeedService,
                drawingSeedPolicy,
                drawingEngine,
                inputHashGenerator,
                resultHashGenerator,
                inputV2HashGenerator,
                resultV2HashGenerator,
                new WeightedPrizeV1AllocationEngine(),
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
        when(historyRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void 원본을_결정적으로_재현한_뒤_새_Seed로_정확히_10명을_뽑으면_검증에_성공한다() {
        List<CandidateValue> candidates = LongStream.rangeClosed(1, 100)
                .mapToObj(memberId -> new CandidateValue(memberId, 1L))
                .toList();
        VerifiedSnapshot snapshot = VerifiedSnapshotTestFactory.create(
                SNAPSHOT_ID, EVENT_ID, 10, "WEIGHTED", "WEIGHTED_V1", candidates);
        DrawingSeed originalSeed = DrawingSeed.from(ORIGINAL_SEED);
        DrawInput originalInput = input(snapshot, originalSeed, 10);
        DrawingHash inputHash = inputHashGenerator.generate(originalInput);

        List<Winner> storedWinners = LongStream.rangeClosed(1, 10)
                .mapToObj(memberId -> winner(memberId, (int) memberId))
                .toList();
        DrawOutput storedOutput = new DrawOutput(
                DrawingAlgorithmVersion.WEIGHTED_V1,
                LongStream.rangeClosed(1, 10)
                        .mapToObj(memberId -> new DrawWinner(memberId, (int) memberId, 1L))
                        .toList()
        );
        DrawingHash resultHash = resultHashGenerator.generate(inputHash.value(), storedOutput);
        Drawing drawing = drawing(10, inputHash, resultHash);

        when(drawingRepository.findById(DRAWING_ID)).thenReturn(Optional.of(drawing));
        when(snapshotIntegrityService.verifyForReplay(SNAPSHOT_ID)).thenReturn(snapshot);
        when(exclusionRepository.findMemberIdsByDrawingId(DRAWING_ID)).thenReturn(List.of());
        when(drawingSeedService.reuseForRetry(SEED_ID))
                .thenReturn(new PersistedDrawingSeed(SEED_ID, originalSeed));
        when(winnerRepository.findAllByDrawingIdOrderByRankInDrawingAsc(DRAWING_ID))
                .thenReturn(storedWinners);
        DrawingSeed replaySeed = DrawingSeed.from(REPLAY_SEED);
        when(drawingSeedPolicy.createForVerification(originalSeed)).thenReturn(replaySeed);
        DrawOutput cardinalityReplayOutput = new DrawOutput(
                DrawingAlgorithmVersion.WEIGHTED_V1,
                LongStream.rangeClosed(11, 20)
                        .mapToObj(memberId -> new DrawWinner(memberId, (int) memberId - 10, 1L))
                        .toList()
        );
        when(drawingEngine.draw(any())).thenReturn(storedOutput, cardinalityReplayOutput);

        DrawingVerificationResult result = service.verify(DRAWING_ID, ADMIN_ID);

        assertThat(result.status()).isEqualTo(DrawingVerificationStatus.VERIFIED);
        assertThat(result.expectedWinnerCount()).isEqualTo(10);
        assertThat(result.actualWinnerCount()).isEqualTo(10);
        assertThat(result.winnerCountMatched()).isTrue();
        assertThat(result.winnersUnique()).isTrue();
        assertThat(result.candidatesMatched()).isTrue();
        assertThat(result.exclusionsMatched()).isTrue();
        assertThat(result.ranksMatched()).isTrue();
        assertThat(result.resultHashMatched()).isTrue();
        assertThat(result.algorithmMatched()).isTrue();
        assertThat(result.failureCode()).isNull();
        verify(drawingEngine, times(2)).draw(any());
        verify(historyRepository).save(any(DrawingVerificationHistory.class));
    }

    @Test
    void 원본_Seed_재실행의_Winner와_Rank가_다르면_검증에_실패한다() {
        List<CandidateValue> candidates = LongStream.rangeClosed(1, 12)
                .mapToObj(memberId -> new CandidateValue(memberId, 1L))
                .toList();
        VerifiedSnapshot snapshot = VerifiedSnapshotTestFactory.create(
                SNAPSHOT_ID, EVENT_ID, 10, "WEIGHTED", "WEIGHTED_V1", candidates);
        DrawingSeed originalSeed = DrawingSeed.from(ORIGINAL_SEED);
        DrawInput originalInput = input(snapshot, originalSeed, 10);
        DrawingHash inputHash = inputHashGenerator.generate(originalInput);
        List<Winner> storedWinners = LongStream.rangeClosed(1, 10)
                .mapToObj(memberId -> winner(memberId, (int) memberId))
                .toList();
        DrawOutput storedOutput = new DrawOutput(
                DrawingAlgorithmVersion.WEIGHTED_V1,
                LongStream.rangeClosed(1, 10)
                        .mapToObj(memberId -> new DrawWinner(memberId, (int) memberId, 1L))
                        .toList()
        );
        DrawingHash resultHash = resultHashGenerator.generate(inputHash.value(), storedOutput);
        Drawing drawing = drawing(10, inputHash, resultHash);

        when(drawingRepository.findById(DRAWING_ID)).thenReturn(Optional.of(drawing));
        when(snapshotIntegrityService.verifyForReplay(SNAPSHOT_ID)).thenReturn(snapshot);
        when(exclusionRepository.findMemberIdsByDrawingId(DRAWING_ID)).thenReturn(List.of());
        when(drawingSeedService.reuseForRetry(SEED_ID))
                .thenReturn(new PersistedDrawingSeed(SEED_ID, originalSeed));
        when(winnerRepository.findAllByDrawingIdOrderByRankInDrawingAsc(DRAWING_ID))
                .thenReturn(storedWinners);
        when(drawingEngine.draw(originalInput)).thenReturn(new DrawOutput(
                DrawingAlgorithmVersion.WEIGHTED_V1,
                LongStream.rangeClosed(2, 11)
                        .mapToObj(memberId -> new DrawWinner(memberId, (int) memberId - 1, 1L))
                        .toList()
        ));

        DrawingVerificationResult result = service.verify(DRAWING_ID, ADMIN_ID);

        assertThat(result.status()).isEqualTo(DrawingVerificationStatus.VERIFICATION_FAILED);
        assertThat(result.resultHashMatched()).isFalse();
        assertThat(result.failureCode()).isEqualTo("DETERMINISTIC_REPLAY_MISMATCH");
        verify(drawingSeedPolicy, never()).createForVerification(any());
        verify(historyRepository).save(any(DrawingVerificationHistory.class));
    }

    @Test
    void 저장된_Input_Hash가_다르면_독립_재실행하지_않고_실패_이력을_남긴다() {
        List<CandidateValue> candidates = List.of(
                new CandidateValue(1L, 1L),
                new CandidateValue(2L, 1L)
        );
        VerifiedSnapshot snapshot = VerifiedSnapshotTestFactory.create(
                SNAPSHOT_ID, EVENT_ID, 1, "WEIGHTED", "WEIGHTED_V1", candidates);
        Drawing drawing = mock(Drawing.class);
        when(drawing.getId()).thenReturn(DRAWING_ID);
        when(drawing.getStatus()).thenReturn(DrawingStatus.COMPLETED);
        when(drawing.getSnapshotId()).thenReturn(SNAPSHOT_ID);
        when(drawing.getEventId()).thenReturn(EVENT_ID);
        when(drawing.getWinnerCount()).thenReturn(1);
        when(drawing.getDrawMethod()).thenReturn("WEIGHTED");
        when(drawing.getAlgorithmVersion()).thenReturn("WEIGHTED_V1");
        when(drawing.getPrizeAlgorithmVersion()).thenReturn("PRIZE_WEIGHTED_V1");
        when(drawing.getSeedId()).thenReturn(SEED_ID);
        when(drawing.getInputHash()).thenReturn("f".repeat(64));

        when(drawingRepository.findById(DRAWING_ID)).thenReturn(Optional.of(drawing));
        when(snapshotIntegrityService.verifyForReplay(SNAPSHOT_ID)).thenReturn(snapshot);
        when(exclusionRepository.findMemberIdsByDrawingId(DRAWING_ID)).thenReturn(List.of());
        when(drawingSeedService.reuseForRetry(SEED_ID)).thenReturn(
                new PersistedDrawingSeed(SEED_ID, DrawingSeed.from(ORIGINAL_SEED)));

        DrawingVerificationResult result = service.verify(DRAWING_ID, ADMIN_ID);

        assertThat(result.status()).isEqualTo(DrawingVerificationStatus.VERIFICATION_FAILED);
        assertThat(result.failureCode()).isEqualTo("STORED_INPUT_INTEGRITY_FAILED");
        assertThat(result.actualWinnerCount()).isNull();
        verify(drawingEngine, never()).draw(any());
        verify(historyRepository).save(any(DrawingVerificationHistory.class));
    }

    /** REDRAW 재현 검증은 Snapshot 전체 상품 풀이 아닌 고정 결원 상품을 그대로 사용한다. */
    @Test
    void REDRAW는_고정_결원_Winner의_상품으로_재현_검증한다() {
        PrizeValue firstPrize = new PrizeValue(501L, "FIRST", "1등 상품", 1, 100L, 1);
        PrizeValue secondPrize = new PrizeValue(502L, "SECOND", "2등 상품", 2, 1L, 1);
        VerifiedSnapshot snapshot = VerifiedSnapshotTestFactory.create(
                SNAPSHOT_ID, EVENT_ID, 2, "WEIGHTED", "WEIGHTED_V1",
                List.of(new CandidateValue(1L, 1L), new CandidateValue(2L, 1L), new CandidateValue(3L, 1L)),
                List.of(firstPrize, secondPrize));
        DrawingSeed originalSeed = DrawingSeed.from(ORIGINAL_SEED);
        DrawInput input = input(snapshot, originalSeed, 1);
        DrawingHash inputHash = inputV2HashGenerator.generate(input, snapshot.prizeAlgorithmVersion(), List.of(secondPrize));
        DrawOutput output = new DrawOutput(DrawingAlgorithmVersion.WEIGHTED_V1, List.of(new DrawWinner(3L, 1, 1L)));
        PrizeAllocationOutput prizeOutput = new PrizeAllocationOutput(PrizeAllocationAlgorithmVersion.PRIZE_WEIGHTED_V1,
                List.of(new AllocatedPrize(3L, 1, secondPrize)));
        DrawingHash resultHash = resultV2HashGenerator.generate(inputHash.value(), output, prizeOutput);
        Drawing drawing = drawing(1, inputHash, resultHash);
        when(drawing.getDrawType()).thenReturn(DrawingType.REDRAW);
        when(drawing.getRedrawRequestId()).thenReturn(50L);
        Winner storedWinner = winner(3L, 1);
        when(storedWinner.getSnapshotPrizeId()).thenReturn(secondPrize.snapshotPrizeId());
        when(storedWinner.getPrizeKey()).thenReturn(secondPrize.prizeKey());
        when(storedWinner.getPrizeDisplayName()).thenReturn(secondPrize.displayName());
        when(storedWinner.getPrizePriority()).thenReturn(secondPrize.priority());

        when(drawingRepository.findById(DRAWING_ID)).thenReturn(Optional.of(drawing));
        when(snapshotIntegrityService.verifyForReplay(SNAPSHOT_ID)).thenReturn(snapshot);
        when(exclusionRepository.findMemberIdsByDrawingId(DRAWING_ID)).thenReturn(List.of());
        when(redrawDrawingQueryRepository.findVacancyPrizeSourcesByRequestId(50L))
                .thenReturn(List.of(new RedrawVacancyPrizeSource(100L, secondPrize.snapshotPrizeId())));
        when(drawingSeedService.reuseForRetry(SEED_ID)).thenReturn(new PersistedDrawingSeed(SEED_ID, originalSeed));
        when(winnerRepository.findAllByDrawingIdOrderByRankInDrawingAsc(DRAWING_ID)).thenReturn(List.of(storedWinner));
        when(drawingSeedPolicy.createForVerification(originalSeed)).thenReturn(DrawingSeed.from(REPLAY_SEED));
        when(drawingEngine.draw(any())).thenReturn(output);

        DrawingVerificationResult result = service.verify(DRAWING_ID, ADMIN_ID);

        assertThat(result.status()).isEqualTo(DrawingVerificationStatus.VERIFIED);
        verify(redrawDrawingQueryRepository, times(1)).findVacancyPrizeSourcesByRequestId(50L);
    }

    private DrawInput input(VerifiedSnapshot snapshot, DrawingSeed seed, int winnerCount) {
        return new DrawInput(
                EVENT_ID,
                SNAPSHOT_ID,
                snapshot.snapshotHash(),
                seed,
                "WEIGHTED_V1",
                winnerCount,
                snapshot.candidates(),
                Set.of()
        );
    }

    private Drawing drawing(int winnerCount, DrawingHash inputHash, DrawingHash resultHash) {
        Drawing drawing = mock(Drawing.class);
        when(drawing.getId()).thenReturn(DRAWING_ID);
        when(drawing.getStatus()).thenReturn(DrawingStatus.COMPLETED);
        when(drawing.getSnapshotId()).thenReturn(SNAPSHOT_ID);
        when(drawing.getEventId()).thenReturn(EVENT_ID);
        when(drawing.getWinnerCount()).thenReturn(winnerCount);
        when(drawing.getDrawMethod()).thenReturn("WEIGHTED");
        when(drawing.getAlgorithmVersion()).thenReturn("WEIGHTED_V1");
        when(drawing.getPrizeAlgorithmVersion()).thenReturn("PRIZE_WEIGHTED_V1");
        when(drawing.getSeedId()).thenReturn(SEED_ID);
        when(drawing.getInputHash()).thenReturn(inputHash.value());
        when(drawing.getInputPayload()).thenReturn(inputHash.canonicalPayload());
        when(drawing.getResultHash()).thenReturn(resultHash.value());
        when(drawing.getOutputPayload()).thenReturn(resultHash.canonicalPayload());
        return drawing;
    }

    private Winner winner(long memberId, int rank) {
        Winner winner = mock(Winner.class);
        when(winner.getMemberId()).thenReturn(memberId);
        when(winner.getRankInDrawing()).thenReturn(rank);
        when(winner.getAppliedTicketCount()).thenReturn(1L);
        return winner;
    }
}
