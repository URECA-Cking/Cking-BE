package kr.co.cking.drawing.application;

import java.time.Clock;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.response.PageResponse;
import kr.co.cking.drawing.domain.Drawing;
import kr.co.cking.drawing.domain.DrawingErrorCode;
import kr.co.cking.drawing.domain.DrawingStatus;
import kr.co.cking.drawing.domain.DrawingVerificationFailureCode;
import kr.co.cking.drawing.domain.DrawingVerificationHistory;
import kr.co.cking.drawing.domain.DrawingVerificationStatus;
import kr.co.cking.drawing.domain.engine.DrawInput;
import kr.co.cking.drawing.domain.engine.DrawOutput;
import kr.co.cking.drawing.domain.engine.DrawWinner;
import kr.co.cking.drawing.domain.engine.DrawingAlgorithmVersion;
import kr.co.cking.drawing.domain.engine.DrawingEngine;
import kr.co.cking.drawing.domain.hash.DrawInputHashGenerator;
import kr.co.cking.drawing.domain.hash.DrawResultHashGenerator;
import kr.co.cking.drawing.domain.hash.DrawingHash;
import kr.co.cking.drawing.domain.seed.DrawingSeed;
import kr.co.cking.drawing.repository.DrawingExclusionQueryRepository;
import kr.co.cking.drawing.repository.DrawingRepository;
import kr.co.cking.drawing.repository.DrawingVerificationHistoryRepository;
import kr.co.cking.member.application.MemberQueryService;
import kr.co.cking.snapshot.application.SnapshotIntegrityService;
import kr.co.cking.snapshot.application.VerifiedSnapshot;
import kr.co.cking.snapshot.domain.CandidateValue;
import kr.co.cking.winner.domain.Winner;
import kr.co.cking.winner.repository.WinnerRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 저장 결과 무결성과 새 Seed 기반 독립 재실행의 인원 수 계약을 함께 검증한다. */
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
    private final SnapshotIntegrityService snapshotIntegrityService;
    private final DrawingSeedService drawingSeedService;
    private final DrawingSeedPolicy drawingSeedPolicy;
    private final DrawingEngine drawingEngine;
    private final DrawInputHashGenerator inputHashGenerator;
    private final DrawResultHashGenerator resultHashGenerator;
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

        Set<Long> exclusions = Set.copyOf(exclusionRepository.findMemberIdsByDrawingId(drawing.getId()));
        DrawingSeed originalSeed = drawingSeedService.reuseForRetry(drawing.getSeedId()).seed();
        DrawInput originalInput = toInput(drawing, snapshot, originalSeed, exclusions);

        DrawingHash originalInputHash = inputHashGenerator.generate(originalInput);
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
        DrawingHash storedResultHash = resultHashGenerator.generate(originalInputHash.value(), storedOutput);
        evidence.resultHashMatched = Objects.equals(drawing.getResultHash(), storedResultHash.value())
                && Objects.equals(drawing.getOutputPayload(), storedResultHash.canonicalPayload())
                && hasValidOutputContract(originalInput, storedOutput);
        if (!evidence.resultHashMatched) {
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
        boolean matched = Objects.equals(drawing.getSnapshotId(), snapshot.snapshotId())
                && Objects.equals(drawing.getEventId(), snapshot.eventId())
                && drawing.getWinnerCount() == snapshot.winnerCount()
                && Objects.equals(drawing.getDrawMethod(), snapshot.drawMethod())
                && Objects.equals(drawing.getAlgorithmVersion(), snapshot.algorithmVersion());
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
            algorithmMatched = output.algorithmVersion().name().equals(input.algorithmVersion());
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
