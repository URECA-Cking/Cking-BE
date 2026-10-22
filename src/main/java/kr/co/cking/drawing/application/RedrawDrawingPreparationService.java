package kr.co.cking.drawing.application;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.drawing.domain.DrawAttemptHistory;
import kr.co.cking.drawing.domain.Drawing;
import kr.co.cking.drawing.domain.DrawingErrorCode;
import kr.co.cking.drawing.domain.DrawingStatus;
import kr.co.cking.drawing.domain.DrawingType;
import kr.co.cking.drawing.domain.RedrawExclusion;
import kr.co.cking.drawing.domain.RedrawExclusionReason;
import kr.co.cking.drawing.domain.engine.DrawInput;
import kr.co.cking.drawing.domain.hash.DrawInputHashGenerator;
import kr.co.cking.drawing.domain.hash.DrawInputV2HashGenerator;
import kr.co.cking.drawing.domain.hash.DrawingHash;
import kr.co.cking.drawing.repository.DrawAttemptHistoryRepository;
import kr.co.cking.drawing.repository.DrawingRepository;
import kr.co.cking.drawing.repository.RedrawDrawingQueryRepository;
import kr.co.cking.drawing.repository.RedrawExclusionRepository;
import kr.co.cking.drawing.repository.RedrawExclusionSource;
import kr.co.cking.drawing.repository.RedrawVacancyPrizeSource;
import kr.co.cking.event.application.EventDrawingQueryService;
import kr.co.cking.snapshot.application.SnapshotIntegrityService;
import kr.co.cking.snapshot.application.VerifiedSnapshot;
import kr.co.cking.snapshot.domain.PrizeValue;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** REDRAW의 변경 불가능한 Drawing·Seed·입력과 첫 Attempt를 결과 실행 전에 확정한다. */
@Service
@RequiredArgsConstructor
class RedrawDrawingPreparationService {

    private final DrawingRepository drawingRepository;
    private final EventDrawingQueryService eventDrawingQueryService;
    private final SnapshotIntegrityService snapshotIntegrityService;
    private final DrawingSeedService drawingSeedService;
    private final RedrawExclusionRepository redrawExclusionRepository;
    private final RedrawDrawingQueryRepository redrawQueryRepository;
    private final DrawAttemptHistoryRepository attemptRepository;
    private final DrawInputHashGenerator inputHashGenerator;
    private final DrawInputV2HashGenerator inputV2HashGenerator;
    private final Clock clock;

    @Transactional
    public RedrawDrawingPreparation prepare(
            Long requestId,
            Long adminId,
            Long originalDrawingId,
            int vacancyCount
    ) {
        Drawing initial = drawingRepository.findById(originalDrawingId)
                .orElseThrow(() -> new BusinessException(DrawingErrorCode.INVALID_STATE));
        if (initial.getDrawType() != DrawingType.INITIAL || initial.getStatus() != DrawingStatus.COMPLETED) {
            throw new BusinessException(DrawingErrorCode.INVALID_STATE);
        }

        // 같은 Event의 회차 계산과 REDRAW 생성을 직렬화한다.
        eventDrawingQueryService.getDrawingSourceForUpdate(initial.getEventId());
        Drawing existing = drawingRepository.findByRedrawRequestId(requestId).orElse(null);
        if (existing != null) {
            return existing(existing);
        }
        if (hasRunningOrRetryableFailedRedraw(initial.getEventId())) {
            throw new BusinessException(DrawingErrorCode.CONCURRENT_COMMAND);
        }

        VerifiedSnapshot snapshot = snapshotIntegrityService.verifyForDrawing(initial.getEventId());
        if (!initial.getSnapshotId().equals(snapshot.snapshotId())) {
            throw new BusinessException(DrawingErrorCode.INVALID_STATE);
        }
        List<RedrawExclusionSource> exclusionSources = redrawQueryRepository
                .findExclusionSourcesByEventId(initial.getEventId());
        Set<Long> excluded = exclusionSources.stream()
                .map(RedrawExclusionSource::memberId)
                .collect(Collectors.toUnmodifiableSet());
        long eligibleCount = snapshot.candidates().stream()
                .filter(candidate -> !excluded.contains(candidate.memberId()))
                .count();
        if (eligibleCount < vacancyCount) {
            return RedrawDrawingPreparation.terminal(RedrawDrawingExecutionResult.noCandidates());
        }

        Drawing previous = drawingRepository.findTopByEventIdOrderByDrawNoDesc(initial.getEventId())
                .orElseThrow();
        PersistedDrawingSeed seed = drawingSeedService.createForRedraw(previous.getSeedId());
        Drawing redraw = drawingRepository.saveAndFlush(Drawing.createRedraw(
                initial, previous.getDrawNo() + 1, requestId, seed.seedId(), vacancyCount, adminId));
        redrawExclusionRepository.saveAllAndFlush(exclusionSources.stream()
                .map(source -> RedrawExclusion.of(redraw.getId(), source.memberId(),
                        RedrawExclusionReason.from(source.managementStatus())))
                .toList());

        DrawInput input = new DrawInput(
                initial.getEventId(), snapshot.snapshotId(), snapshot.snapshotHash(), seed.seed(),
                initial.getAlgorithmVersion(), vacancyCount, snapshot.candidates(), excluded);
        List<PrizeValue> inheritedPrizes = inheritedPrizes(snapshot, requestId, vacancyCount);
        DrawingHash inputHash = inheritedPrizes.isEmpty()
                ? inputHashGenerator.generate(input)
                : inputV2HashGenerator.generate(input, snapshot.prizeAlgorithmVersion(), toPrizePool(inheritedPrizes));
        Instant startedAt = clock.instant();
        redraw.start(inputHash.canonicalPayload(), inputHash.value(), startedAt);
        attemptRepository.saveAndFlush(DrawAttemptHistory.started(
                redraw.getId(), redraw.getAttemptCount(), adminId, startedAt));
        drawingRepository.flush();
        return RedrawDrawingPreparation.started(
                new DrawingRetryRequest(redraw.getId(), redraw.getAttemptCount()));
    }

    private RedrawDrawingPreparation existing(Drawing drawing) {
        return switch (drawing.getStatus()) {
            case COMPLETED -> RedrawDrawingPreparation.terminal(
                    RedrawDrawingExecutionResult.executed(drawing.getId()));
            case RUNNING -> RedrawDrawingPreparation.started(
                    new DrawingRetryRequest(drawing.getId(), drawing.getAttemptCount()));
            case FAILED -> RedrawDrawingPreparation.terminal(
                    RedrawDrawingExecutionResult.failed(drawing.getId()));
            case READY -> throw new BusinessException(DrawingErrorCode.CONCURRENT_COMMAND);
        };
    }

    /**
     * 저장된 제외 명단으로 Retry할 수 있는 FAILED REDRAW가 있으면 후속 REDRAW의 Winner 확정을 막는다.
     * 그렇지 않으면 후속 결과가 Retry 후보에 새로 들어가 확정 입력을 더 이상 재현할 수 없게 된다.
     */
    private boolean hasRunningOrRetryableFailedRedraw(Long eventId) {
        return drawingRepository.findAllRedrawByEventIdAndStatusInForUpdate(
                        eventId, List.of(DrawingStatus.RUNNING, DrawingStatus.FAILED))
                .stream()
                .anyMatch(drawing -> drawing.getStatus() == DrawingStatus.RUNNING
                        || attemptRepository.findFirstByDrawingIdOrderByAttemptNoDesc(drawing.getId())
                        .map(DrawAttemptHistory::isRetryable)
                        // 이력이 유실된 FAILED Drawing은 안전을 위해 운영자 확인 전까지 후속 확정을 막는다.
                        .orElse(true));
    }

    private List<PrizeValue> inheritedPrizes(
            VerifiedSnapshot snapshot,
            Long requestId,
            int vacancyCount
    ) {
        if (snapshot.prizes().isEmpty()) {
            return List.of();
        }
        List<RedrawVacancyPrizeSource> sources = redrawQueryRepository
                .findVacancyPrizeSourcesByRequestId(requestId);
        if (sources.size() != vacancyCount) {
            throw new IllegalStateException("REDRAW 결원과 승계 상품 원본 수가 일치하지 않습니다.");
        }
        Map<Long, PrizeValue> prizesById = snapshot.prizes().stream()
                .filter(prize -> prize.snapshotPrizeId() != null)
                .collect(Collectors.toMap(PrizeValue::snapshotPrizeId, prize -> prize));
        return sources.stream().map(source -> {
            PrizeValue prize = prizesById.get(source.snapshotPrizeId());
            if (prize == null) {
                throw new IllegalStateException("고정 결원 Winner의 상품이 공식 Snapshot과 일치하지 않습니다.");
            }
            return prize;
        }).toList();
    }

    private List<PrizeValue> toPrizePool(List<PrizeValue> inheritedPrizes) {
        Map<Long, Long> quantities = inheritedPrizes.stream().collect(Collectors.groupingBy(
                PrizeValue::snapshotPrizeId, LinkedHashMap::new, Collectors.counting()));
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
}
