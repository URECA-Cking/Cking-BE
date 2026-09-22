package kr.co.cking.drawing.application;

import java.time.Clock;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.drawing.domain.Drawing;
import kr.co.cking.drawing.domain.DrawingErrorCode;
import kr.co.cking.drawing.domain.DrawingStatus;
import kr.co.cking.drawing.domain.RedrawExclusion;
import kr.co.cking.drawing.domain.RedrawExclusionReason;
import kr.co.cking.drawing.domain.DrawingType;
import kr.co.cking.drawing.domain.engine.DrawInput;
import kr.co.cking.drawing.domain.engine.DrawOutput;
import kr.co.cking.drawing.domain.engine.DrawWinner;
import kr.co.cking.drawing.domain.engine.DrawingEngine;
import kr.co.cking.drawing.domain.hash.DrawInputHashGenerator;
import kr.co.cking.drawing.domain.hash.DrawInputV2HashGenerator;
import kr.co.cking.drawing.domain.hash.DrawResultHashGenerator;
import kr.co.cking.drawing.domain.hash.DrawResultV2HashGenerator;
import kr.co.cking.drawing.domain.hash.DrawingHash;
import kr.co.cking.drawing.domain.prize.AllocatedPrize;
import kr.co.cking.drawing.domain.prize.PrizeAllocationEngine;
import kr.co.cking.drawing.domain.prize.PrizeAllocationInput;
import kr.co.cking.drawing.domain.prize.PrizeAllocationOutput;
import kr.co.cking.drawing.repository.DrawingRepository;
import kr.co.cking.drawing.repository.RedrawExclusionRepository;
import kr.co.cking.drawing.repository.RedrawExclusionSource;
import kr.co.cking.event.application.EventDrawingQueryService;
import kr.co.cking.snapshot.application.SnapshotIntegrityService;
import kr.co.cking.snapshot.application.VerifiedSnapshot;
import kr.co.cking.snapshot.domain.PrizeValue;
import kr.co.cking.winner.domain.Winner;
import kr.co.cking.winner.domain.WinnerManagement;
import kr.co.cking.winner.repository.WinnerManagementRepository;
import kr.co.cking.winner.repository.WinnerRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** INITIAL Snapshot과 기존 Winner 제외 규칙으로 전체 REDRAW Drawing을 생성하는 시스템3 구현체다. */
@Service
@RequiredArgsConstructor(onConstructor_ = @org.springframework.beans.factory.annotation.Autowired)
public class DefaultRedrawDrawingExecutionService implements RedrawDrawingExecutionService {
    private final DrawingRepository drawingRepository;
    private final EventDrawingQueryService eventDrawingQueryService;
    private final SnapshotIntegrityService snapshotIntegrityService;
    private final DrawingSeedService drawingSeedService;
    private final DrawingEngine drawingEngine;
    private final DrawInputHashGenerator inputHashGenerator;
    private final DrawResultHashGenerator resultHashGenerator;
    private final DrawInputV2HashGenerator inputV2HashGenerator;
    private final DrawResultV2HashGenerator resultV2HashGenerator;
    private final PrizeAllocationEngine prizeAllocationEngine;
    private final WinnerRepository winnerRepository;
    private final WinnerManagementRepository winnerManagementRepository;
    private final RedrawExclusionRepository redrawExclusionRepository;
    private final Clock clock;

    /** 기존 V1 단위 테스트와 상품 없는 REDRAW 경로를 위한 호환 생성자다. */
    public DefaultRedrawDrawingExecutionService(
            DrawingRepository drawingRepository,
            EventDrawingQueryService eventDrawingQueryService,
            SnapshotIntegrityService snapshotIntegrityService,
            DrawingSeedService drawingSeedService,
            DrawingEngine drawingEngine,
            DrawInputHashGenerator inputHashGenerator,
            DrawResultHashGenerator resultHashGenerator,
            WinnerRepository winnerRepository,
            WinnerManagementRepository winnerManagementRepository,
            RedrawExclusionRepository redrawExclusionRepository,
            Clock clock
    ) {
        this(drawingRepository, eventDrawingQueryService, snapshotIntegrityService, drawingSeedService, drawingEngine, inputHashGenerator,
                resultHashGenerator, new DrawInputV2HashGenerator(), new DrawResultV2HashGenerator(),
                new kr.co.cking.drawing.domain.prize.WeightedPrizeV1AllocationEngine(), winnerRepository,
                winnerManagementRepository, redrawExclusionRepository, clock);
    }

    /** 원본 INITIAL Snapshot으로 전체 후보를 재추첨하고 기존 Winner는 모두 후보에서 제외한다. */
    @Override
    @Transactional
    public RedrawDrawingExecutionResult execute(Long requestId, Long adminId, Long originalDrawingId, int vacancyCount) {
        Drawing initial = drawingRepository.findById(originalDrawingId)
                .orElseThrow(() -> new BusinessException(DrawingErrorCode.INVALID_STATE));
        if (initial.getDrawType() != DrawingType.INITIAL || initial.getStatus() != DrawingStatus.COMPLETED
                || drawingRepository.findByRedrawRequestId(requestId).isPresent()) {
            throw new BusinessException(DrawingErrorCode.INVALID_STATE);
        }
        // 같은 Event의 서로 다른 REDRAW 요청도 최신 회차 계산부터 생성까지 직렬화한다.
        eventDrawingQueryService.getDrawingSourceForUpdate(initial.getEventId());
        VerifiedSnapshot snapshot = snapshotIntegrityService.verifyForDrawing(initial.getEventId());
        if (!initial.getSnapshotId().equals(snapshot.snapshotId())) {
            throw new BusinessException(DrawingErrorCode.INVALID_STATE);
        }
        List<RedrawExclusionSource> exclusionSources = winnerRepository
                .findRedrawExclusionSourcesByEventId(initial.getEventId());
        Set<Long> excluded = exclusionSources.stream()
                .map(RedrawExclusionSource::memberId)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        long eligibleCount = snapshot.candidates().stream().filter(candidate -> !excluded.contains(candidate.memberId())).count();
        if (eligibleCount < vacancyCount) {
            return RedrawDrawingExecutionResult.noCandidates();
        }
        Drawing previousDrawing = drawingRepository.findTopByEventIdOrderByDrawNoDesc(initial.getEventId()).orElseThrow();
        PersistedDrawingSeed seed = drawingSeedService.createForRedraw(previousDrawing.getSeedId());
        int drawNo = previousDrawing.getDrawNo() + 1;
        Drawing redraw = drawingRepository.saveAndFlush(Drawing.createRedraw(initial, drawNo, requestId, seed.seedId(), vacancyCount, adminId));
        redrawExclusionRepository.saveAll(exclusionSources.stream()
                .map(source -> RedrawExclusion.of(redraw.getId(), source.memberId(),
                        RedrawExclusionReason.from(source.managementStatus())))
                .toList());
        DrawInput input = new DrawInput(initial.getEventId(), snapshot.snapshotId(), snapshot.snapshotHash(), seed.seed(),
                initial.getAlgorithmVersion(), vacancyCount, snapshot.candidates(), excluded);
        boolean hasPrizes = !snapshot.prizes().isEmpty();
        DrawingHash inputHash = hasPrizes
                ? inputV2HashGenerator.generate(input, snapshot.prizeAlgorithmVersion(), snapshot.prizes())
                : inputHashGenerator.generate(input);
        redraw.start(inputHash.canonicalPayload(), inputHash.value(), clock.instant());
        DrawOutput output = drawingEngine.draw(input);
        validateOutput(input, output);
        PrizeAllocationOutput prizeOutput = hasPrizes
                ? prizeAllocationEngine.allocate(new PrizeAllocationInput(seed.seed(), snapshot.prizeAlgorithmVersion(),
                        output.winners(), snapshot.prizes()))
                : null;
        DrawingHash resultHash = hasPrizes
                ? resultV2HashGenerator.generate(inputHash.value(), output, prizeOutput)
                : resultHashGenerator.generate(inputHash.value(), output);
        List<Winner> winners = toWinners(redraw, snapshot, output, prizeOutput);
        List<Winner> saved = winnerRepository.saveAllAndFlush(winners);
        winnerManagementRepository.saveAllAndFlush(saved.stream().map(winner -> WinnerManagement.selected(winner.getId())).toList());
        redraw.complete(resultHash.canonicalPayload(), resultHash.value(), clock.instant());
        return RedrawDrawingExecutionResult.executed(redraw.getId());
    }

    private void validateOutput(DrawInput input, DrawOutput output) {
        if (!output.algorithmVersion().name().equals(input.algorithmVersion())
                || output.winners().size() != input.winnerCount()) {
            throw new IllegalStateException("REDRAW 결과의 당첨자 수 또는 알고리즘이 확정 입력과 일치하지 않습니다.");
        }
        Set<Integer> expectedRanks = new java.util.HashSet<>();
        for (int rank = 1; rank <= input.winnerCount(); rank++) {
            expectedRanks.add(rank);
        }
        Set<Integer> actualRanks = output.winners().stream().map(DrawWinner::rank).collect(Collectors.toSet());
        if (!actualRanks.equals(expectedRanks)) {
            throw new IllegalStateException("REDRAW 결과의 Rank가 연속적이지 않습니다.");
        }
    }

    private List<Winner> toWinners(
            Drawing drawing,
            VerifiedSnapshot snapshot,
            DrawOutput output,
            PrizeAllocationOutput prizeOutput
    ) {
        Map<Integer, AllocatedPrize> allocations = prizeOutput == null ? Map.of()
                : prizeOutput.allocations().stream().collect(Collectors.toMap(
                        AllocatedPrize::rank, java.util.function.Function.identity()));
        validatePrizeAllocations(snapshot, output, allocations);
        return output.winners().stream().sorted(Comparator.comparingInt(DrawWinner::rank))
                .map(winner -> toWinner(drawing, snapshot, winner, allocations.get(winner.rank())))
                .toList();
    }

    private void validatePrizeAllocations(
            VerifiedSnapshot snapshot,
            DrawOutput output,
            Map<Integer, AllocatedPrize> allocations
    ) {
        if (snapshot.prizes().isEmpty()) {
            if (!allocations.isEmpty()) {
                throw new IllegalStateException("상품이 없는 공식 Snapshot에는 상품을 배정할 수 없습니다.");
            }
            return;
        }
        if (allocations.size() != output.winners().size()) {
            throw new IllegalStateException("모든 REDRAW 당첨자에게 공식 Snapshot 상품이 배정되어야 합니다.");
        }
        Map<Long, PrizeValue> officialPrizes = snapshot.prizes().stream()
                .filter(prize -> prize.snapshotPrizeId() != null)
                .collect(Collectors.toMap(PrizeValue::snapshotPrizeId, java.util.function.Function.identity()));
        for (AllocatedPrize allocation : allocations.values()) {
            if (!allocation.prize().equals(officialPrizes.get(allocation.prize().snapshotPrizeId()))) {
                throw new IllegalStateException("배정 상품은 REDRAW의 공식 Snapshot에 포함되어야 합니다.");
            }
        }
    }

    private Winner toWinner(
            Drawing drawing,
            VerifiedSnapshot snapshot,
            DrawWinner winner,
            AllocatedPrize allocation
    ) {
        if (allocation != null && !allocation.memberId().equals(winner.memberId())) {
            throw new IllegalStateException("REDRAW 당첨자와 상품 배정 결과가 일치하지 않습니다.");
        }
        return Winner.create(drawing.getEventId(), drawing.getId(), winner.memberId(), winner.rank(),
                winner.appliedTicketCount(), allocation == null ? null : snapshot.snapshotId(),
                allocation == null ? null : allocation.prize());
    }
}
