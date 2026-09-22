package kr.co.cking.drawing.application;

import java.time.Clock;
import java.util.Set;
import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.drawing.domain.DrawAttemptHistory;
import kr.co.cking.drawing.domain.Drawing;
import kr.co.cking.drawing.domain.DrawingErrorCode;
import kr.co.cking.drawing.domain.DrawingSnapshotContract;
import kr.co.cking.drawing.domain.engine.DrawInput;
import kr.co.cking.drawing.domain.hash.DrawInputHashGenerator;
import kr.co.cking.drawing.domain.hash.DrawInputV2HashGenerator;
import kr.co.cking.drawing.domain.hash.DrawingHash;
import kr.co.cking.drawing.repository.DrawAttemptHistoryRepository;
import kr.co.cking.drawing.repository.DrawingRepository;
import kr.co.cking.event.application.EventDrawingQueryService;
import kr.co.cking.event.application.dto.EventDrawingSource;
import kr.co.cking.event.domain.EventErrorCode;
import kr.co.cking.event.domain.EventStatus;
import kr.co.cking.member.application.MemberQueryService;
import kr.co.cking.snapshot.application.SnapshotIntegrityService;
import kr.co.cking.snapshot.application.VerifiedSnapshot;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** INITIAL Drawing과 첫 Attempt를 결과 실행보다 먼저 확정해 실행 실패도 복구 가능하게 만든다. */
@Service
@RequiredArgsConstructor
class InitialDrawingPreparationService {

    private static final int INITIAL_DRAW_NO = 0;

    private final MemberQueryService memberQueryService;
    private final EventDrawingQueryService eventDrawingQueryService;
    private final SnapshotIntegrityService snapshotIntegrityService;
    private final DrawingSeedService drawingSeedService;
    private final DrawingRepository drawingRepository;
    private final DrawAttemptHistoryRepository attemptRepository;
    private final DrawInputHashGenerator inputHashGenerator;
    private final DrawInputV2HashGenerator inputV2HashGenerator;
    private final Clock clock;

    @Transactional
    public InitialDrawingPreparation prepare(Long adminId, Long eventId) {
        memberQueryService.validateAdmin(adminId);
        EventDrawingSource event = eventDrawingQueryService.getDrawingSourceForUpdate(eventId);
        // 이벤트 행을 먼저 잠갔으므로 같은 이벤트의 INITIAL 생성은 이미 직렬화된다.
        // 존재하지 않는 Drawing을 FOR UPDATE로 조회하면 MySQL gap lock과 INSERT가 교착할 수 있어 일반 조회를 사용한다.
        Drawing existing = drawingRepository.findByEventIdAndDrawNo(eventId, INITIAL_DRAW_NO)
                .orElse(null);
        if (existing != null) {
            return handleExisting(existing, event.status());
        }
        if (event.deletedAt() != null || event.status() != EventStatus.CLOSED) {
            throw new BusinessException(EventErrorCode.INVALID_STATE);
        }

        VerifiedSnapshot snapshot = snapshotIntegrityService.verifyForDrawing(eventId);
        PersistedDrawingSeed seed = drawingSeedService.createForInitial();
        Drawing drawing = drawingRepository.saveAndFlush(Drawing.createInitial(
                DrawingSnapshotContract.from(snapshot), seed.seedId(), adminId));
        DrawInput input = new DrawInput(
                snapshot.eventId(), snapshot.snapshotId(), snapshot.snapshotHash(), seed.seed(),
                snapshot.algorithmVersion(), snapshot.winnerCount(), snapshot.candidates(), Set.of());
        DrawingHash inputHash = snapshot.prizes().isEmpty()
                ? inputHashGenerator.generate(input)
                : inputV2HashGenerator.generate(input, snapshot.prizeAlgorithmVersion(), snapshot.prizes());
        drawing.start(inputHash.canonicalPayload(), inputHash.value(), clock.instant());
        attemptRepository.saveAndFlush(DrawAttemptHistory.started(
                drawing.getId(), drawing.getAttemptCount(), adminId, clock.instant()));
        drawingRepository.flush();
        return InitialDrawingPreparation.started(
                new DrawingRetryRequest(drawing.getId(), drawing.getAttemptCount()));
    }

    private InitialDrawingPreparation handleExisting(Drawing drawing, EventStatus eventStatus) {
        return switch (drawing.getStatus()) {
            case COMPLETED -> {
                if (eventStatus != EventStatus.DRAW_COMPLETED && eventStatus != EventStatus.PUBLISHED) {
                    throw new BusinessException(DrawingErrorCode.INVALID_STATE);
                }
                yield InitialDrawingPreparation.existing(InitialDrawingResult.from(drawing));
            }
            case READY -> throw new BusinessException(DrawingErrorCode.CONCURRENT_COMMAND);
            // 같은 최초 실행 요청은 기존 Attempt에 합류한다. 실제 결과 실행은 Drawing 행 잠금으로 직렬화된다.
            case RUNNING -> InitialDrawingPreparation.started(
                    new DrawingRetryRequest(drawing.getId(), drawing.getAttemptCount()));
            case FAILED -> throw new BusinessException(DrawingErrorCode.INVALID_STATE);
        };
    }
}
