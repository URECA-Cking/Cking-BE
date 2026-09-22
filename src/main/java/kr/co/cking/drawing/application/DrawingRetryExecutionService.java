package kr.co.cking.drawing.application;

import java.time.Clock;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.drawing.domain.DrawAttemptHistory;
import kr.co.cking.drawing.domain.DrawAttemptStatus;
import kr.co.cking.drawing.domain.Drawing;
import kr.co.cking.drawing.domain.DrawingErrorCode;
import kr.co.cking.drawing.domain.DrawingFailureStage;
import kr.co.cking.drawing.domain.DrawingStatus;
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
import kr.co.cking.drawing.repository.DrawAttemptHistoryRepository;
import kr.co.cking.drawing.repository.DrawingExclusionQueryRepository;
import kr.co.cking.drawing.repository.DrawingRepository;
import kr.co.cking.drawing.repository.RedrawDrawingQueryRepository;
import kr.co.cking.drawing.repository.RedrawVacancyPrizeSource;
import kr.co.cking.event.application.service.EventCommandService;
import kr.co.cking.redraw.application.RedrawDrawingLifecycleService;
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

/** 확정된 Drawing 입력을 재구성해 Winner·Hash·후속 상태를 하나의 트랜잭션으로 저장한다. */
@Service
@RequiredArgsConstructor
class DrawingRetryExecutionService {

    private final DrawingRepository drawingRepository;
    private final DrawAttemptHistoryRepository attemptRepository;
    private final SnapshotIntegrityService snapshotIntegrityService;
    private final DrawingSeedService drawingSeedService;
    private final DrawingExclusionQueryRepository exclusionRepository;
    private final RedrawDrawingQueryRepository redrawQueryRepository;
    private final DrawingEngine drawingEngine;
    private final PrizeAllocationEngine prizeAllocationEngine;
    private final DrawInputHashGenerator inputHashGenerator;
    private final DrawInputV2HashGenerator inputV2HashGenerator;
    private final DrawResultHashGenerator resultHashGenerator;
    private final DrawResultV2HashGenerator resultV2HashGenerator;
    private final WinnerRepository winnerRepository;
    private final WinnerManagementRepository winnerManagementRepository;
    private final EventCommandService eventCommandService;
    private final RedrawDrawingLifecycleService redrawLifecycleService;
    private final Clock clock;

    @Transactional
    public DrawingRetryResult execute(DrawingRetryRequest request) {
        Drawing drawing = drawingRepository.findByIdForRetry(request.drawingId())
                .orElseThrow(() -> new BusinessException(DrawingErrorCode.DRAWING_NOT_FOUND));
        DrawAttemptHistory attempt = attemptRepository
                .findByDrawingIdAndAttemptNo(request.drawingId(), request.attemptNo())
                .orElseThrow(() -> new IllegalStateException("Retry Attempt를 찾을 수 없습니다."));
        if (drawing.getStatus() == DrawingStatus.COMPLETED
                && drawing.getAttemptCount() == request.attemptNo()
                && attempt.getStatus() == DrawAttemptStatus.SUCCEEDED) {
            return DrawingRetryResult.from(drawing);
        }
        requireRunningAttempt(drawing, attempt, request);

        VerifiedSnapshot snapshot = stage(DrawingFailureStage.INPUT_VERIFICATION,
                () -> snapshotIntegrityService.verifyForReplay(drawing.getSnapshotId()));
        PersistedDrawingSeed seed = stage(DrawingFailureStage.INPUT_VERIFICATION,
                () -> drawingSeedService.reuseForRetry(drawing.getSeedId()));
        Set<Long> exclusions = stage(DrawingFailureStage.INPUT_VERIFICATION,
                () -> Set.copyOf(exclusionRepository.findMemberIdsByDrawingId(drawing.getId())));
        DrawInput input = stage(DrawingFailureStage.INPUT_VERIFICATION,
                () -> createAndValidateInput(drawing, snapshot, seed, exclusions));
        List<PrizeValue> executionPrizes = stage(DrawingFailureStage.INPUT_VERIFICATION,
                () -> resolveExecutionPrizes(drawing, snapshot));
        DrawingHash inputHash = stage(DrawingFailureStage.INPUT_VERIFICATION,
                () -> generateAndValidateInputHash(drawing, snapshot, input, executionPrizes));

        DrawOutput output = stage(DrawingFailureStage.DRAWING_ENGINE, () -> drawingEngine.draw(input));
        stage(DrawingFailureStage.DRAWING_ENGINE, () -> {
            validateOutput(input, output);
            return null;
        });
        PrizeAllocationOutput prizeOutput = stage(DrawingFailureStage.PRIZE_ALLOCATION,
                () -> allocatePrizes(drawing, snapshot, seed, output, executionPrizes));
        DrawingHash resultHash = stage(DrawingFailureStage.RESULT_PERSISTENCE,
                () -> generateResultHash(snapshot, inputHash, output, prizeOutput));

        stage(DrawingFailureStage.RESULT_PERSISTENCE, () -> {
            saveWinners(drawing, snapshot, output, prizeOutput);
            drawing.complete(resultHash.canonicalPayload(), resultHash.value(), clock.instant());
            attempt.succeed(clock.instant());
            drawingRepository.flush();
            return null;
        });
        stage(DrawingFailureStage.EVENT_TRANSITION, () -> {
            completeFollowUpState(drawing);
            return null;
        });
        return DrawingRetryResult.from(drawing);
    }

    private void requireRunningAttempt(
            Drawing drawing,
            DrawAttemptHistory attempt,
            DrawingRetryRequest request
    ) {
        if (drawing.getStatus() != DrawingStatus.RUNNING
                || drawing.getAttemptCount() != request.attemptNo()
                || attempt.getStatus() != DrawAttemptStatus.STARTED) {
            throw new BusinessException(DrawingErrorCode.CONCURRENT_COMMAND);
        }
    }

    private DrawInput createAndValidateInput(
            Drawing drawing,
            VerifiedSnapshot snapshot,
            PersistedDrawingSeed seed,
            Set<Long> exclusions
    ) {
        boolean contractMismatch = !drawing.getEventId().equals(snapshot.eventId())
                || !drawing.getSnapshotId().equals(snapshot.snapshotId())
                || !drawing.getAlgorithmVersion().equals(snapshot.algorithmVersion())
                || !drawing.getPrizeAlgorithmVersion().equals(snapshot.prizeAlgorithmVersion())
                || (drawing.getDrawType() == DrawingType.INITIAL && drawing.getWinnerCount() != snapshot.winnerCount())
                || (drawing.getDrawType() == DrawingType.INITIAL && !exclusions.isEmpty());
        long availableCandidates = snapshot.candidates().stream()
                .filter(candidate -> !exclusions.contains(candidate.memberId()))
                .count();
        if (contractMismatch || drawing.getWinnerCount() > availableCandidates) {
            throw new BusinessException(DrawingErrorCode.NON_RETRYABLE_FAILURE);
        }
        return new DrawInput(
                drawing.getEventId(), drawing.getSnapshotId(), snapshot.snapshotHash(), seed.seed(),
                drawing.getAlgorithmVersion(), drawing.getWinnerCount(), snapshot.candidates(), exclusions
        );
    }

    private DrawingHash generateAndValidateInputHash(
            Drawing drawing,
            VerifiedSnapshot snapshot,
            DrawInput input,
            List<PrizeValue> executionPrizes
    ) {
        List<PrizeValue> inputPrizePool = drawing.getDrawType() == DrawingType.REDRAW
                ? toPrizePool(executionPrizes)
                : executionPrizes;
        DrawingHash generated = inputPrizePool.isEmpty()
                ? inputHashGenerator.generate(input)
                : inputV2HashGenerator.generate(input, drawing.getPrizeAlgorithmVersion(), inputPrizePool);
        if (drawing.getInputHash() == null || drawing.getInputPayload() == null
                || !drawing.getInputHash().equals(generated.value())
                || !drawing.getInputPayload().equals(generated.canonicalPayload())) {
            throw new BusinessException(DrawingErrorCode.NON_RETRYABLE_FAILURE);
        }
        return generated;
    }

    private PrizeAllocationOutput allocatePrizes(
            Drawing drawing,
            VerifiedSnapshot snapshot,
            PersistedDrawingSeed seed,
            DrawOutput output,
            List<PrizeValue> executionPrizes
    ) {
        if (executionPrizes.isEmpty()) {
            return null;
        }
        if (drawing.getDrawType() == DrawingType.REDRAW) {
            return inheritPrizes(snapshot, output, executionPrizes);
        }
        return prizeAllocationEngine.allocate(
                new PrizeAllocationInput(seed.seed(), snapshot.prizeAlgorithmVersion(),
                        output.winners(), executionPrizes));
    }

    private DrawingHash generateResultHash(
            VerifiedSnapshot snapshot,
            DrawingHash inputHash,
            DrawOutput output,
            PrizeAllocationOutput prizeOutput
    ) {
        return prizeOutput == null
                ? resultHashGenerator.generate(inputHash.value(), output)
                : resultV2HashGenerator.generate(inputHash.value(), output, prizeOutput);
    }

    private void validateOutput(DrawInput input, DrawOutput output) {
        if (!output.algorithmVersion().name().equals(input.algorithmVersion())
                || output.winners().size() != input.winnerCount()) {
            throw new IllegalStateException("DrawingEngine 결과가 확정 입력 계약과 일치하지 않습니다.");
        }
        Set<Integer> expectedRanks = new HashSet<>();
        for (int rank = 1; rank <= input.winnerCount(); rank++) {
            expectedRanks.add(rank);
        }
        Set<Integer> actualRanks = output.winners().stream().map(DrawWinner::rank).collect(Collectors.toSet());
        if (!actualRanks.equals(expectedRanks)) {
            throw new IllegalStateException("DrawingEngine 결과의 Rank가 연속적이지 않습니다.");
        }
    }

    private void saveWinners(
            Drawing drawing,
            VerifiedSnapshot snapshot,
            DrawOutput output,
            PrizeAllocationOutput prizeOutput
    ) {
        if (!winnerRepository.findAllByDrawingIdOrderByRankInDrawingAsc(drawing.getId()).isEmpty()) {
            throw new IllegalStateException("FAILED Drawing에 부분 Winner가 남아 있습니다.");
        }
        Map<Integer, AllocatedPrize> allocations = prizeOutput == null ? Map.of()
                : prizeOutput.allocations().stream().collect(Collectors.toMap(
                        AllocatedPrize::rank, java.util.function.Function.identity()));
        validatePrizeAllocations(snapshot, output, allocations);
        List<Winner> winners = output.winners().stream()
                .sorted(Comparator.comparingInt(DrawWinner::rank))
                .map(winner -> toWinner(drawing, snapshot, winner, allocations.get(winner.rank())))
                .toList();
        winners.forEach(winner -> {
            if (winnerRepository.existsByEventIdAndMemberId(drawing.getEventId(), winner.getMemberId())) {
                throw new BusinessException(DrawingErrorCode.NON_RETRYABLE_FAILURE);
            }
        });
        List<Winner> saved = winnerRepository.saveAllAndFlush(winners);
        winnerManagementRepository.saveAllAndFlush(saved.stream()
                .map(winner -> WinnerManagement.selected(winner.getId())).toList());
    }

    private void validatePrizeAllocations(
            VerifiedSnapshot snapshot,
            DrawOutput output,
            Map<Integer, AllocatedPrize> allocations
    ) {
        if (snapshot.prizes().isEmpty()) {
            if (!allocations.isEmpty()) {
                throw new IllegalStateException("상품이 없는 Snapshot에는 상품을 배정할 수 없습니다.");
            }
            return;
        }
        if (allocations.size() != output.winners().size()) {
            throw new IllegalStateException("모든 당첨자에게 Snapshot 상품이 배정되어야 합니다.");
        }
        Map<Long, PrizeValue> officialPrizes = snapshot.prizes().stream()
                .collect(Collectors.toMap(PrizeValue::snapshotPrizeId, java.util.function.Function.identity()));
        for (AllocatedPrize allocation : allocations.values()) {
            if (!allocation.prize().equals(officialPrizes.get(allocation.prize().snapshotPrizeId()))) {
                throw new IllegalStateException("배정 상품은 Drawing의 공식 Snapshot에 포함되어야 합니다.");
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
            throw new IllegalStateException("당첨자와 상품 배정 결과가 일치하지 않습니다.");
        }
        return Winner.create(
                drawing.getEventId(), drawing.getId(), winner.memberId(), winner.rank(),
                winner.appliedTicketCount(), allocation == null ? null : snapshot.snapshotId(),
                allocation == null ? null : allocation.prize()
        );
    }

    private void completeFollowUpState(Drawing drawing) {
        if (drawing.getDrawType() == DrawingType.INITIAL) {
            eventCommandService.completeDrawing(drawing.getEventId());
            return;
        }
        redrawLifecycleService.complete(drawing.getRedrawRequestId(), clock.instant());
    }

    /** REDRAW는 Snapshot 전체 상품이 아니라 요청이 고정한 결원 상품만 rank 순으로 승계한다. */
    private List<PrizeValue> resolveExecutionPrizes(Drawing drawing, VerifiedSnapshot snapshot) {
        if (drawing.getDrawType() == DrawingType.INITIAL || snapshot.prizes().isEmpty()) {
            return snapshot.prizes();
        }
        List<RedrawVacancyPrizeSource> sources = redrawQueryRepository
                .findVacancyPrizeSourcesByRequestId(drawing.getRedrawRequestId());
        if (sources.size() != drawing.getWinnerCount()) {
            throw new BusinessException(DrawingErrorCode.NON_RETRYABLE_FAILURE);
        }
        Map<Long, PrizeValue> prizesById = snapshot.prizes().stream()
                .collect(Collectors.toMap(PrizeValue::snapshotPrizeId, prize -> prize));
        return sources.stream().map(source -> {
            PrizeValue prize = prizesById.get(source.snapshotPrizeId());
            if (prize == null) {
                throw new BusinessException(DrawingErrorCode.NON_RETRYABLE_FAILURE);
            }
            return prize;
        }).toList();
    }

    private PrizeAllocationOutput inheritPrizes(
            VerifiedSnapshot snapshot,
            DrawOutput output,
            List<PrizeValue> inheritedPrizes
    ) {
        if (output.winners().size() != inheritedPrizes.size()) {
            throw new BusinessException(DrawingErrorCode.NON_RETRYABLE_FAILURE);
        }
        List<DrawWinner> winners = output.winners().stream()
                .sorted(Comparator.comparingInt(DrawWinner::rank))
                .toList();
        List<AllocatedPrize> allocations = java.util.stream.IntStream.range(0, winners.size())
                .mapToObj(index -> new AllocatedPrize(
                        winners.get(index).memberId(), winners.get(index).rank(), inheritedPrizes.get(index)))
                .toList();
        return new PrizeAllocationOutput(
                kr.co.cking.drawing.domain.prize.PrizeAllocationAlgorithmVersion.from(
                        snapshot.prizeAlgorithmVersion()),
                allocations);
    }

    private List<PrizeValue> toPrizePool(List<PrizeValue> inheritedPrizes) {
        Map<Long, Long> quantities = inheritedPrizes.stream().collect(Collectors.groupingBy(
                PrizeValue::snapshotPrizeId, java.util.LinkedHashMap::new, Collectors.counting()));
        return quantities.entrySet().stream().map(entry -> {
            PrizeValue prize = inheritedPrizes.stream()
                    .filter(value -> value.snapshotPrizeId().equals(entry.getKey()))
                    .findFirst()
                    .orElseThrow();
            return new PrizeValue(
                    prize.snapshotPrizeId(), prize.prizeKey(), prize.displayName(), prize.priority(),
                    prize.weight(), Math.toIntExact(entry.getValue()));
        }).toList();
    }

    private <T> T stage(DrawingFailureStage stage, Supplier<T> action) {
        try {
            return action.get();
        } catch (DrawingExecutionFailure failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw DrawingExecutionFailure.at(stage, failure);
        }
    }
}
