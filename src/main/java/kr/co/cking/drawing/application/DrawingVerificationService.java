package kr.co.cking.drawing.application;

import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.response.PageResponse;
import kr.co.cking.drawing.domain.Drawing;
import kr.co.cking.drawing.domain.DrawingErrorCode;
import kr.co.cking.drawing.domain.DrawingStatus;
import kr.co.cking.drawing.domain.DrawingType;
import kr.co.cking.drawing.domain.DrawingVerificationFailureCode;
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
import kr.co.cking.drawing.domain.prize.AllocatedPrize;
import kr.co.cking.drawing.domain.prize.PrizeAllocationAlgorithmVersion;
import kr.co.cking.drawing.domain.prize.PrizeAllocationEngine;
import kr.co.cking.drawing.domain.prize.PrizeAllocationInput;
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
import kr.co.cking.snapshot.domain.CandidateValue;
import kr.co.cking.snapshot.domain.PrizeValue;
import kr.co.cking.winner.domain.Winner;
import kr.co.cking.winner.repository.WinnerRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 원본 Seed 결정적 재현과 새 Seed 기반 독립 재실행의 인원 수 계약을 함께 검증한다. */
@Service
@RequiredArgsConstructor
public class DrawingVerificationService {

    private static final Sort HISTORY_SORT = Sort.by(
            Sort.Order.desc("verifiedAt"),
            Sort.Order.desc("id")
    );

    private final MemberQueryService memberQueryService;
    private final DrawingRepository drawingRepository;
    private final WinnerRepository winnerRepository;
    private final DrawingVerificationHistoryRepository historyRepository;
    private final DrawingExclusionQueryRepository exclusionRepository;
    private final RedrawDrawingQueryRepository redrawDrawingQueryRepository;
    private final SnapshotIntegrityService snapshotIntegrityService;
    private final DrawingSeedService drawingSeedService;
    private final DrawingSeedPolicy drawingSeedPolicy;
    private final DrawingEngine drawingEngine;
    private final DrawInputHashGenerator inputHashGenerator;
    private final DrawResultHashGenerator resultHashGenerator;
    private final DrawInputV2HashGenerator inputV2HashGenerator;
    private final DrawResultV2HashGenerator resultV2HashGenerator;
    private final PrizeAllocationEngine prizeAllocationEngine;
    private final Clock clock;

    @Transactional
    public DrawingVerificationResult verify(Long drawingId, Long adminId) {
        memberQueryService.validateAdmin(adminId);
        Drawing drawing = findCompletedDrawing(drawingId);
        VerificationEvidence evidence = new VerificationEvidence(drawing.getWinnerCount());

        try {
            verify(drawing, evidence);
        } catch (VerificationFailure failure) {
            evidence.fail(failure.code, failure.getMessage());
        } catch (BusinessException exception) {
            evidence.fail(mapBusinessFailure(exception), exception.getMessage());
        } catch (IllegalArgumentException exception) {
            evidence.fail(DrawingVerificationFailureCode.UNSUPPORTED_ALGORITHM_VERSION, exception.getMessage());
        } catch (RuntimeException exception) {
            evidence.fail(DrawingVerificationFailureCode.VERIFICATION_EXECUTION_FAILED, exception.getMessage());
        }

        DrawingVerificationHistory saved = historyRepository.save(evidence.toHistory(
                drawing.getId(), adminId, clock.instant()));
        return DrawingVerificationResult.from(saved);
    }

    @Transactional(readOnly = true)
    public PageResponse<DrawingVerificationResult> getHistory(
            Long drawingId,
            Long adminId,
            int page,
            int size
    ) {
        memberQueryService.validateAdmin(adminId);
        findDrawing(drawingId);
        Page<DrawingVerificationResult> result = historyRepository
                .findAllByDrawingId(drawingId, PageRequest.of(page, size, HISTORY_SORT))
                .map(DrawingVerificationResult::from);
        return PageResponse.from(result);
    }

    private void verify(Drawing drawing, VerificationEvidence evidence) {
        VerifiedSnapshot snapshot = snapshotIntegrityService.verifyForReplay(drawing.getSnapshotId());
        evidence.snapshotHashMatched = true;
        requireDrawingContract(drawing, snapshot);
        List<PrizeValue> inheritedPrizes = drawing.getDrawType() == DrawingType.REDRAW
                ? inheritedPrizes(drawing, snapshot)
                : List.of();
        List<PrizeValue> prizes = prizesFor(drawing, snapshot, inheritedPrizes);

        Set<Long> exclusions = Set.copyOf(exclusionRepository.findMemberIdsByDrawingId(drawing.getId()));
        DrawingSeed originalSeed = drawingSeedService.reuseForRetry(drawing.getSeedId()).seed();
        DrawInput originalInput = toInput(drawing, snapshot, originalSeed, exclusions);

        DrawingHash originalInputHash = generateInputHash(originalInput, snapshot, prizes);
        evidence.inputHashMatched = Objects.equals(drawing.getInputHash(), originalInputHash.value())
                && Objects.equals(drawing.getInputPayload(), originalInputHash.canonicalPayload());
        if (!evidence.inputHashMatched) {
            throw new VerificationFailure(
                    DrawingVerificationFailureCode.STORED_INPUT_INTEGRITY_FAILED,
                    "저장된 추첨 입력 Payload 또는 Hash가 보존 입력과 일치하지 않습니다."
            );
        }

        DrawingAlgorithmVersion algorithmVersion = DrawingAlgorithmVersion.from(drawing.getAlgorithmVersion());
        List<Winner> storedWinners = winnerRepository
                .findAllByDrawingIdOrderByRankInDrawingAsc(drawing.getId());
        DrawOutput storedOutput = new DrawOutput(
                algorithmVersion,
                storedWinners.stream().map(this::toDrawWinner).toList()
        );
        PrizeAllocationOutput storedPrizeOutput = prizes.isEmpty()
                ? null
                : toStoredPrizeOutput(drawing, snapshot, storedWinners);
        DrawingHash storedResultHash = generateResultHash(
                originalInputHash.value(), storedOutput, storedPrizeOutput);
        boolean storedResultIntegrityMatched = Objects.equals(drawing.getResultHash(), storedResultHash.value())
                && Objects.equals(drawing.getOutputPayload(), storedResultHash.canonicalPayload())
                && hasValidOutputContract(originalInput, storedOutput)
                && hasValidPrizeOutputContract(storedOutput, storedPrizeOutput);
        if (!storedResultIntegrityMatched) {
            throw new VerificationFailure(
                    DrawingVerificationFailureCode.STORED_RESULT_INTEGRITY_FAILED,
                    "저장된 당첨 결과, Payload 또는 Hash가 보존 입력 계약과 일치하지 않습니다."
            );
        }

        long eligibleCount = snapshot.candidates().stream()
                .map(CandidateValue::memberId)
                .filter(memberId -> !exclusions.contains(memberId))
                .count();
        if (eligibleCount < drawing.getWinnerCount()) {
            throw new VerificationFailure(
                    DrawingVerificationFailureCode.INSUFFICIENT_CANDIDATES,
                    "제외 대상을 반영한 후보 수가 보존된 winnerCount보다 적습니다."
            );
        }

        DrawOutput deterministicOutput = drawingEngine.draw(originalInput);
        PrizeAllocationOutput deterministicPrizeOutput = allocatePrizes(
                drawing, originalSeed, snapshot, deterministicOutput, prizes, inheritedPrizes);
        DrawingHash deterministicResultHash = generateResultHash(
                originalInputHash.value(), deterministicOutput, deterministicPrizeOutput);
        evidence.algorithmMatched = deterministicOutput.algorithmVersion() == algorithmVersion;
        evidence.resultHashMatched = evidence.algorithmMatched
                && Objects.equals(storedOutput, deterministicOutput)
                && Objects.equals(storedPrizeOutput, deterministicPrizeOutput)
                && Objects.equals(drawing.getResultHash(), deterministicResultHash.value())
                && Objects.equals(drawing.getOutputPayload(), deterministicResultHash.canonicalPayload());
        if (!evidence.resultHashMatched) {
            throw new VerificationFailure(
                    DrawingVerificationFailureCode.DETERMINISTIC_REPLAY_MISMATCH,
                    "원본 Seed 결정적 재실행 결과가 저장된 Winner, Rank 또는 Result Hash와 일치하지 않습니다."
            );
        }

        DrawingSeed replaySeed = drawingSeedPolicy.createForVerification(originalSeed);
        evidence.replaySeed = replaySeed;
        DrawInput replayInput = toInput(drawing, snapshot, replaySeed, exclusions);
        DrawOutput replayOutput = drawingEngine.draw(replayInput);
        evidence.captureReplay(replayInput, replayOutput);

        if (!evidence.replayContractMatched()) {
            throw new VerificationFailure(
                    DrawingVerificationFailureCode.REPLAY_RESULT_INVALID,
                    "독립 재실행 결과가 보존된 당첨 인원 수 또는 후보 조건과 일치하지 않습니다."
            );
        }
        evidence.status = DrawingVerificationStatus.VERIFIED;
    }

    private void requireDrawingContract(Drawing drawing, VerifiedSnapshot snapshot) {
        boolean winnerCountMatched = drawing.getDrawType() == DrawingType.REDRAW
                || drawing.getWinnerCount() == snapshot.winnerCount();
        boolean matched = Objects.equals(drawing.getSnapshotId(), snapshot.snapshotId())
                && Objects.equals(drawing.getEventId(), snapshot.eventId())
                && winnerCountMatched
                && Objects.equals(drawing.getDrawMethod(), snapshot.drawMethod())
                && Objects.equals(drawing.getAlgorithmVersion(), snapshot.algorithmVersion())
                && Objects.equals(drawing.getPrizeAlgorithmVersion(), snapshot.prizeAlgorithmVersion());
        if (!matched) {
            throw new VerificationFailure(
                    DrawingVerificationFailureCode.DRAWING_CONTRACT_MISMATCH,
                    "Drawing 조건이 참조 Snapshot의 확정 조건과 일치하지 않습니다."
            );
        }
    }

    private DrawInput toInput(
            Drawing drawing,
            VerifiedSnapshot snapshot,
            DrawingSeed seed,
            Set<Long> exclusions
    ) {
        return new DrawInput(
                drawing.getEventId(),
                drawing.getSnapshotId(),
                snapshot.snapshotHash(),
                seed,
                drawing.getAlgorithmVersion(),
                drawing.getWinnerCount(),
                snapshot.candidates(),
                exclusions
        );
    }

    private DrawingHash generateInputHash(DrawInput input, VerifiedSnapshot snapshot, List<PrizeValue> prizes) {
        if (prizes.isEmpty()) {
            return inputHashGenerator.generate(input);
        }
        return inputV2HashGenerator.generate(
                input, snapshot.prizeAlgorithmVersion(), prizes);
    }

    private DrawingHash generateResultHash(
            String inputHash,
            DrawOutput output,
            PrizeAllocationOutput prizeOutput
    ) {
        if (prizeOutput == null) {
            return resultHashGenerator.generate(inputHash, output);
        }
        return resultV2HashGenerator.generate(inputHash, output, prizeOutput);
    }

    private PrizeAllocationOutput allocatePrizes(
            Drawing drawing,
            DrawingSeed seed,
            VerifiedSnapshot snapshot,
            DrawOutput output,
            List<PrizeValue> prizes,
            List<PrizeValue> inheritedPrizes
    ) {
        if (prizes.isEmpty()) {
            return null;
        }
        if (drawing.getDrawType() == DrawingType.REDRAW) {
            List<DrawWinner> winners = output.winners().stream()
                    .sorted(java.util.Comparator.comparingInt(DrawWinner::rank))
                    .toList();
            List<AllocatedPrize> allocations = IntStream.range(0, winners.size())
                    .mapToObj(index -> new AllocatedPrize(winners.get(index).memberId(), winners.get(index).rank(),
                            inheritedPrizes.get(index)))
                    .toList();
            return new PrizeAllocationOutput(
                    PrizeAllocationAlgorithmVersion.from(snapshot.prizeAlgorithmVersion()), allocations);
        }
        return prizeAllocationEngine.allocate(new PrizeAllocationInput(
                seed,
                snapshot.prizeAlgorithmVersion(),
                output.winners(),
                prizes
        ));
    }

    private List<PrizeValue> prizesFor(
            Drawing drawing,
            VerifiedSnapshot snapshot,
            List<PrizeValue> inheritedPrizes
    ) {
        if (drawing.getDrawType() != DrawingType.REDRAW) {
            return snapshot.prizes();
        }
        return toPrizePool(inheritedPrizes);
    }

    private List<PrizeValue> inheritedPrizes(Drawing drawing, VerifiedSnapshot snapshot) {
        if (snapshot.prizes().isEmpty()) {
            return List.of();
        }
        List<RedrawVacancyPrizeSource> sources = redrawDrawingQueryRepository
                .findVacancyPrizeSourcesByRequestId(drawing.getRedrawRequestId());
        if (sources.size() != drawing.getWinnerCount()) {
            throw storedResultFailure("REDRAW 결원과 승계 상품 원본 수가 일치하지 않습니다.");
        }
        Map<Long, PrizeValue> prizesById = snapshot.prizes().stream()
                .filter(prize -> prize.snapshotPrizeId() != null)
                .collect(Collectors.toMap(PrizeValue::snapshotPrizeId, java.util.function.Function.identity()));
        return sources.stream().map(source -> {
            PrizeValue prize = prizesById.get(source.snapshotPrizeId());
            if (prize == null) {
                throw storedResultFailure("고정 결원 Winner의 상품이 공식 Snapshot과 일치하지 않습니다.");
            }
            return prize;
        }).toList();
    }

    private List<PrizeValue> toPrizePool(List<PrizeValue> inheritedPrizes) {
        Map<Long, Long> quantities = inheritedPrizes.stream().collect(Collectors.groupingBy(
                PrizeValue::snapshotPrizeId, java.util.LinkedHashMap::new, Collectors.counting()));
        return quantities.entrySet().stream().map(entry -> {
            PrizeValue prize = inheritedPrizes.stream()
                    .filter(value -> value.snapshotPrizeId().equals(entry.getKey()))
                    .findFirst()
                    .orElseThrow();
            return new PrizeValue(prize.snapshotPrizeId(), prize.prizeKey(), prize.displayName(), prize.priority(),
                    prize.weight(), Math.toIntExact(entry.getValue()));
        }).toList();
    }

    private PrizeAllocationOutput toStoredPrizeOutput(
            Drawing drawing,
            VerifiedSnapshot snapshot,
            List<Winner> winners
    ) {
        Map<Long, PrizeValue> prizesById = new HashMap<>();
        for (PrizeValue prize : snapshot.prizes()) {
            if (prize.snapshotPrizeId() == null || prizesById.put(prize.snapshotPrizeId(), prize) != null) {
                throw storedResultFailure("Snapshot 상품 식별자가 누락되었거나 중복되었습니다.");
            }
        }

        List<AllocatedPrize> allocations = winners.stream()
                .map(winner -> toStoredAllocation(winner, prizesById))
                .toList();
        return new PrizeAllocationOutput(
                PrizeAllocationAlgorithmVersion.from(drawing.getPrizeAlgorithmVersion()),
                allocations
        );
    }

    private AllocatedPrize toStoredAllocation(Winner winner, Map<Long, PrizeValue> prizesById) {
        PrizeValue prize = prizesById.get(winner.getSnapshotPrizeId());
        if (prize == null
                || !Objects.equals(winner.getPrizeKey(), prize.prizeKey())
                || !Objects.equals(winner.getPrizeDisplayName(), prize.displayName())
                || !Objects.equals(winner.getPrizePriority(), prize.priority())) {
            throw storedResultFailure("Winner의 배정 상품이 공식 Snapshot 상품과 일치하지 않습니다.");
        }
        return new AllocatedPrize(winner.getMemberId(), winner.getRankInDrawing(), prize);
    }

    private VerificationFailure storedResultFailure(String message) {
        return new VerificationFailure(
                DrawingVerificationFailureCode.STORED_RESULT_INTEGRITY_FAILED,
                message
        );
    }

    private DrawWinner toDrawWinner(Winner winner) {
        return new DrawWinner(
                winner.getMemberId(),
                winner.getRankInDrawing(),
                winner.getAppliedTicketCount()
        );
    }

    private boolean hasValidOutputContract(DrawInput input, DrawOutput output) {
        Set<Long> candidates = input.candidates().stream()
                .map(CandidateValue::memberId)
                .collect(Collectors.toSet());
        Set<Long> memberIds = output.winners().stream()
                .map(DrawWinner::memberId)
                .collect(Collectors.toSet());
        Set<Integer> ranks = output.winners().stream()
                .map(DrawWinner::rank)
                .collect(Collectors.toSet());
        return output.winners().size() == input.winnerCount()
                && memberIds.size() == output.winners().size()
                && candidates.containsAll(memberIds)
                && memberIds.stream().noneMatch(input.excludedMemberIds()::contains)
                && ranks.equals(expectedRanks(input.winnerCount()));
    }

    private boolean hasValidPrizeOutputContract(
            DrawOutput output,
            PrizeAllocationOutput prizeOutput
    ) {
        if (prizeOutput == null) {
            return true;
        }
        Map<Integer, Long> winnerByRank = output.winners().stream()
                .collect(Collectors.toMap(DrawWinner::rank, DrawWinner::memberId));
        return prizeOutput.allocations().size() == output.winners().size()
                && prizeOutput.allocations().stream().allMatch(allocation ->
                        Objects.equals(winnerByRank.get(allocation.rank()), allocation.memberId()));
    }

    private Set<Integer> expectedRanks(int winnerCount) {
        return IntStream.rangeClosed(1, winnerCount).boxed().collect(Collectors.toSet());
    }

    private Drawing findCompletedDrawing(Long drawingId) {
        Drawing drawing = findDrawing(drawingId);
        if (drawing.getStatus() != DrawingStatus.COMPLETED) {
            throw new BusinessException(DrawingErrorCode.DRAWING_NOT_COMPLETED);
        }
        return drawing;
    }

    private Drawing findDrawing(Long drawingId) {
        return drawingRepository.findById(drawingId)
                .orElseThrow(() -> new BusinessException(DrawingErrorCode.DRAWING_NOT_FOUND));
    }

    private DrawingVerificationFailureCode mapBusinessFailure(BusinessException exception) {
        if ("SNAPSHOT_NOT_FOUND".equals(exception.getErrorCode().code())
                || "SNAPSHOT_HASH_MISMATCH".equals(exception.getErrorCode().code())) {
            return DrawingVerificationFailureCode.SNAPSHOT_INTEGRITY_FAILED;
        }
        return DrawingVerificationFailureCode.VERIFICATION_EXECUTION_FAILED;
    }

    private final class VerificationEvidence {

        private final int expectedWinnerCount;
        private DrawingVerificationStatus status = DrawingVerificationStatus.VERIFICATION_FAILED;
        private DrawingSeed replaySeed;
        private Integer actualWinnerCount;
        private Boolean winnerCountMatched;
        private Boolean winnersUnique;
        private Boolean candidatesMatched;
        private Boolean exclusionsMatched;
        private Boolean ranksMatched;
        private boolean snapshotHashMatched;
        private boolean inputHashMatched;
        private boolean resultHashMatched;
        private boolean algorithmMatched;
        private String failureCode;
        private String failureMessage;

        private VerificationEvidence(int expectedWinnerCount) {
            this.expectedWinnerCount = expectedWinnerCount;
        }

        private void captureReplay(DrawInput input, DrawOutput output) {
            actualWinnerCount = output.winners().size();
            winnerCountMatched = actualWinnerCount == expectedWinnerCount;
            Set<Long> memberIds = output.winners().stream()
                    .map(DrawWinner::memberId)
                    .collect(Collectors.toSet());
            winnersUnique = memberIds.size() == output.winners().size();
            Set<Long> candidateIds = input.candidates().stream()
                    .map(CandidateValue::memberId)
                    .collect(Collectors.toSet());
            candidatesMatched = candidateIds.containsAll(memberIds);
            exclusionsMatched = memberIds.stream().noneMatch(input.excludedMemberIds()::contains);
            Set<Integer> ranks = output.winners().stream()
                    .map(DrawWinner::rank)
                    .collect(Collectors.toSet());
            ranksMatched = ranks.equals(expectedRanks(expectedWinnerCount));
            algorithmMatched = algorithmMatched
                    && output.algorithmVersion().name().equals(input.algorithmVersion());
        }

        private boolean replayContractMatched() {
            return Boolean.TRUE.equals(winnerCountMatched)
                    && Boolean.TRUE.equals(winnersUnique)
                    && Boolean.TRUE.equals(candidatesMatched)
                    && Boolean.TRUE.equals(exclusionsMatched)
                    && Boolean.TRUE.equals(ranksMatched)
                    && algorithmMatched;
        }

        private void fail(DrawingVerificationFailureCode code, String message) {
            status = DrawingVerificationStatus.VERIFICATION_FAILED;
            failureCode = code.name();
            failureMessage = message;
        }

        private DrawingVerificationHistory toHistory(Long drawingId, Long adminId, java.time.Instant verifiedAt) {
            return DrawingVerificationHistory.record(
                    drawingId,
                    status,
                    replaySeed == null ? null : replaySeed.bytes(),
                    expectedWinnerCount,
                    actualWinnerCount,
                    winnerCountMatched,
                    winnersUnique,
                    candidatesMatched,
                    exclusionsMatched,
                    ranksMatched,
                    snapshotHashMatched,
                    inputHashMatched,
                    resultHashMatched,
                    algorithmMatched,
                    failureCode,
                    failureMessage,
                    adminId,
                    verifiedAt
            );
        }
    }

    private static final class VerificationFailure extends RuntimeException {

        private final DrawingVerificationFailureCode code;

        private VerificationFailure(DrawingVerificationFailureCode code, String message) {
            super(message);
            this.code = code;
        }
    }
}
