package kr.co.cking.drawing.application;

import java.time.Clock;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.drawing.domain.Drawing;
import kr.co.cking.drawing.domain.DrawingErrorCode;
import kr.co.cking.drawing.domain.DrawingSnapshotContract;
import kr.co.cking.drawing.domain.engine.DrawInput;
import kr.co.cking.drawing.domain.engine.DrawOutput;
import kr.co.cking.drawing.domain.engine.DrawWinner;
import kr.co.cking.drawing.domain.engine.DrawingEngine;
import kr.co.cking.drawing.domain.hash.DrawInputHashGenerator;
import kr.co.cking.drawing.domain.hash.DrawResultHashGenerator;
import kr.co.cking.drawing.domain.hash.DrawingHash;
import kr.co.cking.drawing.domain.hash.DrawInputV2HashGenerator;
import kr.co.cking.drawing.domain.hash.DrawResultV2HashGenerator;
import kr.co.cking.drawing.domain.prize.AllocatedPrize;
import kr.co.cking.drawing.domain.prize.PrizeAllocationEngine;
import kr.co.cking.drawing.domain.prize.PrizeAllocationInput;
import kr.co.cking.drawing.domain.prize.PrizeAllocationOutput;
import kr.co.cking.drawing.repository.DrawingRepository;
import kr.co.cking.drawing.repository.DrawAttemptHistoryRepository;
import kr.co.cking.drawing.domain.DrawAttemptHistory;
import kr.co.cking.event.application.EventDrawingQueryService;
import kr.co.cking.event.application.dto.EventDrawingSource;
import kr.co.cking.event.application.service.EventCommandService;
import kr.co.cking.event.domain.EventErrorCode;
import kr.co.cking.event.domain.EventStatus;
import kr.co.cking.member.application.MemberQueryService;
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

/** INITIAL Drawing 생성부터 Winner와 Event 상태 확정까지의 원자 실행 경계. */
@Service
@RequiredArgsConstructor(onConstructor_ = @org.springframework.beans.factory.annotation.Autowired)
public class InitialDrawingExecutionService {

    private static final int INITIAL_DRAW_NO = 0;

    private final MemberQueryService memberQueryService;
    private final EventDrawingQueryService eventDrawingQueryService;
    private final SnapshotIntegrityService snapshotIntegrityService;
    private final DrawingSeedService drawingSeedService;
    private final DrawingRepository drawingRepository;
    private final WinnerRepository winnerRepository;
    private final WinnerManagementRepository winnerManagementRepository;
    private final DrawingEngine drawingEngine;
    private final DrawInputHashGenerator inputHashGenerator;
    private final DrawResultHashGenerator resultHashGenerator;
    private final DrawInputV2HashGenerator inputV2HashGenerator;
    private final DrawResultV2HashGenerator resultV2HashGenerator;
    private final PrizeAllocationEngine prizeAllocationEngine;
    private final EventCommandService eventCommandService;
    private final Clock clock;
    private final DrawAttemptHistoryRepository attemptRepository;

    /** 기존 V1 단위 테스트와 상품이 없는 레거시 Drawing 경로를 위한 호환 생성자다. */
    public InitialDrawingExecutionService(MemberQueryService memberQueryService,
            EventDrawingQueryService eventDrawingQueryService, SnapshotIntegrityService snapshotIntegrityService,
            DrawingSeedService drawingSeedService, DrawingRepository drawingRepository,
            WinnerRepository winnerRepository, WinnerManagementRepository winnerManagementRepository,
            DrawingEngine drawingEngine, DrawInputHashGenerator inputHashGenerator,
            DrawResultHashGenerator resultHashGenerator, EventCommandService eventCommandService, Clock clock) {
        this(memberQueryService, eventDrawingQueryService, snapshotIntegrityService, drawingSeedService,
                drawingRepository, winnerRepository, winnerManagementRepository, drawingEngine,
                inputHashGenerator, resultHashGenerator, new DrawInputV2HashGenerator(),
                new DrawResultV2HashGenerator(), new kr.co.cking.drawing.domain.prize.WeightedPrizeV1AllocationEngine(),
                eventCommandService, clock, null);
    }

    /** 상품 추첨이 있는 기존 단위 테스트와 수동 조립 코드를 위한 호환 생성자다. */
    public InitialDrawingExecutionService(MemberQueryService memberQueryService,
            EventDrawingQueryService eventDrawingQueryService, SnapshotIntegrityService snapshotIntegrityService,
            DrawingSeedService drawingSeedService, DrawingRepository drawingRepository,
            WinnerRepository winnerRepository, WinnerManagementRepository winnerManagementRepository,
            DrawingEngine drawingEngine, DrawInputHashGenerator inputHashGenerator,
            DrawResultHashGenerator resultHashGenerator, DrawInputV2HashGenerator inputV2HashGenerator,
            DrawResultV2HashGenerator resultV2HashGenerator, PrizeAllocationEngine prizeAllocationEngine,
            EventCommandService eventCommandService, Clock clock) {
        this(memberQueryService, eventDrawingQueryService, snapshotIntegrityService, drawingSeedService,
                drawingRepository, winnerRepository, winnerManagementRepository, drawingEngine,
                inputHashGenerator, resultHashGenerator, inputV2HashGenerator, resultV2HashGenerator,
                prizeAllocationEngine, eventCommandService, clock, null);
    }

    @Transactional
    public InitialDrawingResult execute(Long adminId, Long eventId) {
        memberQueryService.validateAdmin(adminId);

        // Event 행 잠금으로 같은 Event의 실행 요청을 직렬화한다.
        EventDrawingSource event = eventDrawingQueryService.getDrawingSourceForUpdate(eventId);
        Drawing existing = drawingRepository.findByEventIdAndDrawNoForUpdate(eventId, INITIAL_DRAW_NO)
                .orElse(null);
        if (existing != null) {
            return handleExisting(existing, event.status());
        }

        validateExecutableEvent(event);
        VerifiedSnapshot snapshot = snapshotIntegrityService.verifyForDrawing(eventId);
        PersistedDrawingSeed seed = drawingSeedService.createForInitial();

        Drawing drawing = drawingRepository.saveAndFlush(Drawing.createInitial(
                DrawingSnapshotContract.from(snapshot),
                seed.seedId(),
                adminId
        ));

        DrawInput input = toInput(snapshot, seed);
        boolean hasPrizes = !snapshot.prizes().isEmpty();
        DrawingHash inputHash = hasPrizes
                ? inputV2HashGenerator.generate(input, snapshot.prizeAlgorithmVersion(), snapshot.prizes())
                : inputHashGenerator.generate(input);
        drawing.start(inputHash.canonicalPayload(), inputHash.value(), clock.instant());
        DrawAttemptHistory attempt = DrawAttemptHistory.started(
                drawing.getId(), drawing.getAttemptCount(), adminId, clock.instant());
        if (attemptRepository != null) {
            attemptRepository.saveAndFlush(attempt);
        }

        DrawOutput output = drawingEngine.draw(input);
        validateOutput(input, output);
        PrizeAllocationOutput prizeOutput = hasPrizes
                ? prizeAllocationEngine.allocate(new PrizeAllocationInput(seed.seed(),
                        snapshot.prizeAlgorithmVersion(), output.winners(), snapshot.prizes()))
                : null;
        DrawingHash resultHash = hasPrizes
                ? resultV2HashGenerator.generate(inputHash.value(), output, prizeOutput)
                : resultHashGenerator.generate(inputHash.value(), output);

        saveWinners(drawing, snapshot, output, prizeOutput);
        drawing.complete(resultHash.canonicalPayload(), resultHash.value(), clock.instant());
        attempt.succeed(clock.instant());
        drawingRepository.flush();
        eventCommandService.completeDrawing(eventId);

        return InitialDrawingResult.from(drawing);
    }

    private InitialDrawingResult handleExisting(Drawing drawing, EventStatus eventStatus) {
        return switch (drawing.getStatus()) {
            case COMPLETED -> {
                if (eventStatus != EventStatus.DRAW_COMPLETED && eventStatus != EventStatus.PUBLISHED) {
                    throw new BusinessException(DrawingErrorCode.INVALID_STATE);
                }
                yield InitialDrawingResult.from(drawing);
            }
            case READY, RUNNING -> throw new BusinessException(DrawingErrorCode.CONCURRENT_COMMAND);
            case FAILED -> throw new BusinessException(DrawingErrorCode.INVALID_STATE);
        };
    }

    private void validateExecutableEvent(EventDrawingSource event) {
        if (event.deletedAt() != null || event.status() != EventStatus.CLOSED) {
            throw new BusinessException(EventErrorCode.INVALID_STATE);
        }
    }

    private DrawInput toInput(VerifiedSnapshot snapshot, PersistedDrawingSeed seed) {
        return new DrawInput(
                snapshot.eventId(),
                snapshot.snapshotId(),
                snapshot.snapshotHash(),
                seed.seed(),
                snapshot.algorithmVersion(),
                snapshot.winnerCount(),
                snapshot.candidates(),
                Set.of()
        );
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
        Set<Integer> actualRanks = output.winners().stream()
                .map(DrawWinner::rank)
                .collect(Collectors.toSet());
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
                throw new BusinessException(DrawingErrorCode.INVALID_STATE);
            }
        });

        List<Winner> savedWinners = winnerRepository.saveAllAndFlush(winners);
        List<WinnerManagement> managements = savedWinners.stream()
                .map(winner -> WinnerManagement.selected(winner.getId()))
                .toList();
        winnerManagementRepository.saveAllAndFlush(managements);
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
            throw new IllegalStateException("모든 당첨자에게 공식 Snapshot 상품이 배정되어야 합니다.");
        }

        Map<Long, PrizeValue> officialPrizes = snapshot.prizes().stream()
                .filter(prize -> prize.snapshotPrizeId() != null)
                .collect(Collectors.toMap(PrizeValue::snapshotPrizeId, java.util.function.Function.identity()));
        for (AllocatedPrize allocation : allocations.values()) {
            PrizeValue allocatedPrize = allocation.prize();
            PrizeValue officialPrize = officialPrizes.get(allocatedPrize.snapshotPrizeId());
            if (!allocatedPrize.equals(officialPrize)) {
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
                drawing.getEventId(),
                drawing.getId(),
                winner.memberId(),
                winner.rank(),
                winner.appliedTicketCount(),
                allocation == null ? null : snapshot.snapshotId(),
                allocation == null ? null : allocation.prize()
        );
    }
}
