package kr.co.cking.drawing.application;

import java.time.Clock;
import java.util.List;
import java.util.Set;
import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.drawing.domain.Drawing;
import kr.co.cking.drawing.domain.DrawingErrorCode;
import kr.co.cking.drawing.domain.RedrawExclusion;
import kr.co.cking.drawing.domain.RedrawExclusionReason;
import kr.co.cking.drawing.domain.DrawingType;
import kr.co.cking.drawing.domain.engine.DrawInput;
import kr.co.cking.drawing.domain.engine.DrawOutput;
import kr.co.cking.drawing.domain.engine.DrawingEngine;
import kr.co.cking.drawing.domain.hash.DrawInputHashGenerator;
import kr.co.cking.drawing.domain.hash.DrawResultHashGenerator;
import kr.co.cking.drawing.domain.hash.DrawingHash;
import kr.co.cking.drawing.repository.DrawingRepository;
import kr.co.cking.drawing.repository.RedrawExclusionRepository;
import kr.co.cking.drawing.repository.RedrawExclusionSource;
import kr.co.cking.snapshot.application.SnapshotIntegrityService;
import kr.co.cking.snapshot.application.VerifiedSnapshot;
import kr.co.cking.winner.domain.Winner;
import kr.co.cking.winner.domain.WinnerManagement;
import kr.co.cking.winner.repository.WinnerManagementRepository;
import kr.co.cking.winner.repository.WinnerRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** INITIAL Snapshot과 기존 Winner 제외 규칙으로 전체 REDRAW Drawing을 생성하는 시스템3 구현체다. */
@Service
@RequiredArgsConstructor
public class DefaultRedrawDrawingExecutionService implements RedrawDrawingExecutionService {
    private final DrawingRepository drawingRepository;
    private final SnapshotIntegrityService snapshotIntegrityService;
    private final DrawingSeedService drawingSeedService;
    private final DrawingEngine drawingEngine;
    private final DrawInputHashGenerator inputHashGenerator;
    private final DrawResultHashGenerator resultHashGenerator;
    private final WinnerRepository winnerRepository;
    private final WinnerManagementRepository winnerManagementRepository;
    private final RedrawExclusionRepository redrawExclusionRepository;
    private final Clock clock;

    /** 원본 INITIAL Snapshot으로 전체 후보를 재추첨하고 기존 Winner는 모두 후보에서 제외한다. */
    @Override
    @Transactional
    public RedrawDrawingExecutionResult execute(Long requestId, Long adminId, Long originalDrawingId, int vacancyCount) {
        Drawing initial = drawingRepository.findById(originalDrawingId)
                .orElseThrow(() -> new BusinessException(DrawingErrorCode.INVALID_STATE));
        if (initial.getDrawType() != DrawingType.INITIAL || drawingRepository.findByRedrawRequestId(requestId).isPresent()) {
            throw new BusinessException(DrawingErrorCode.INVALID_STATE);
        }
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
        PersistedDrawingSeed seed = drawingSeedService.createForRedraw(initial.getSeedId());
        int drawNo = drawingRepository.findTopByEventIdOrderByDrawNoDesc(initial.getEventId()).orElseThrow().getDrawNo() + 1;
        Drawing redraw = drawingRepository.saveAndFlush(Drawing.createRedraw(initial, drawNo, requestId, seed.seedId(), vacancyCount, adminId));
        redrawExclusionRepository.saveAll(exclusionSources.stream()
                .map(source -> RedrawExclusion.of(redraw.getId(), source.memberId(),
                        RedrawExclusionReason.from(source.managementStatus())))
                .toList());
        DrawInput input = new DrawInput(initial.getEventId(), snapshot.snapshotId(), snapshot.snapshotHash(), seed.seed(),
                initial.getAlgorithmVersion(), vacancyCount, snapshot.candidates(), excluded);
        DrawingHash inputHash = inputHashGenerator.generate(input);
        redraw.start(inputHash.canonicalPayload(), inputHash.value(), clock.instant());
        DrawOutput output = drawingEngine.draw(input);
        if (output.winners().size() != vacancyCount) {
            throw new IllegalStateException("REDRAW 결과의 당첨자 수가 고정 결원 수와 다릅니다.");
        }
        var winners = output.winners().stream().map(winner -> Winner.create(redraw.getEventId(), redraw.getId(),
                winner.memberId(), winner.rank(), winner.appliedTicketCount())).toList();
        var saved = winnerRepository.saveAllAndFlush(winners);
        winnerManagementRepository.saveAllAndFlush(saved.stream().map(winner -> WinnerManagement.selected(winner.getId())).toList());
        DrawingHash resultHash = resultHashGenerator.generate(inputHash.value(), output);
        redraw.complete(resultHash.canonicalPayload(), resultHash.value(), clock.instant());
        return RedrawDrawingExecutionResult.executed(redraw.getId());
    }
}
