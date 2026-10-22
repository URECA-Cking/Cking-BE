package kr.co.cking.drawing.application;

import java.time.Clock;
import kr.co.cking.drawing.domain.DrawAttemptHistory;
import kr.co.cking.drawing.domain.DrawAttemptStatus;
import kr.co.cking.drawing.domain.Drawing;
import kr.co.cking.drawing.domain.DrawingFailureStage;
import kr.co.cking.drawing.domain.DrawingStatus;
import kr.co.cking.drawing.domain.DrawingType;
import kr.co.cking.drawing.repository.DrawAttemptHistoryRepository;
import kr.co.cking.drawing.repository.DrawingRepository;
import kr.co.cking.redraw.application.RedrawDrawingLifecycleService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 결과 저장 Rollback 뒤 Drawing 실패 상태와 Attempt 실패 이력을 독립 트랜잭션으로 보존한다. */
@Service
@RequiredArgsConstructor
public class DrawingFailurePersistenceService {

    private final DrawingRepository drawingRepository;
    private final DrawAttemptHistoryRepository attemptRepository;
    private final RedrawDrawingLifecycleService redrawLifecycleService;
    private final Clock clock;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailure(
            DrawingRetryRequest request,
            DrawingFailureStage stage,
            String failureCode,
            String failureMessage
    ) {
        Drawing drawing = drawingRepository.findByIdForRetry(request.drawingId()).orElse(null);
        if (drawing == null || drawing.getStatus() != DrawingStatus.RUNNING
                || drawing.getAttemptCount() != request.attemptNo()) {
            return;
        }
        DrawAttemptHistory attempt = attemptRepository
                .findByDrawingIdAndAttemptNo(request.drawingId(), request.attemptNo())
                .orElse(null);
        if (attempt == null || attempt.getStatus() != DrawAttemptStatus.STARTED) {
            return;
        }

        drawing.fail();
        attempt.fail(stage, failureCode, truncate(failureMessage), clock.instant());
        failRedrawRequest(drawing, failureCode, failureMessage);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean markInterrupted(Long drawingId, java.time.Instant cutoff) {
        Drawing drawing = drawingRepository.findByIdForRetry(drawingId).orElse(null);
        if (drawing == null || drawing.getStatus() != DrawingStatus.RUNNING) {
            return false;
        }
        DrawAttemptHistory attempt = attemptRepository.findFirstByDrawingIdOrderByAttemptNoDesc(drawingId)
                .orElse(null);
        if (attempt == null || attempt.getStatus() != DrawAttemptStatus.STARTED
                || !attempt.getStartedAt().isBefore(cutoff)) {
            return false;
        }
        drawing.fail();
        attempt.interrupt(clock.instant());
        failRedrawRequest(drawing, "SERVER_INTERRUPTED", "실행 중 서버가 중단되었습니다.");
        return true;
    }

    private void failRedrawRequest(Drawing drawing, String failureCode, String failureMessage) {
        if (drawing.getDrawType() == DrawingType.REDRAW && drawing.getRedrawRequestId() != null) {
            redrawLifecycleService.fail(
                    drawing.getRedrawRequestId(), clock.instant(), failureCode, failureMessage);
        }
    }

    private String truncate(String message) {
        if (message == null) {
            return null;
        }
        return message.length() <= 2000 ? message : message.substring(0, 2000);
    }
}
