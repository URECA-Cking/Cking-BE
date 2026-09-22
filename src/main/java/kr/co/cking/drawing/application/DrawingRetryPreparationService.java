package kr.co.cking.drawing.application;

import java.time.Clock;
import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.drawing.domain.DrawAttemptHistory;
import kr.co.cking.drawing.domain.Drawing;
import kr.co.cking.drawing.domain.DrawingErrorCode;
import kr.co.cking.drawing.domain.DrawingStatus;
import kr.co.cking.drawing.repository.DrawAttemptHistoryRepository;
import kr.co.cking.drawing.repository.DrawingRepository;
import kr.co.cking.member.application.MemberQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Drawing 행 잠금 안에서 Retry 가능 여부를 재검증하고 새 Attempt를 시작한다. */
@Service
@RequiredArgsConstructor
class DrawingRetryPreparationService {

    private final MemberQueryService memberQueryService;
    private final DrawingRepository drawingRepository;
    private final DrawAttemptHistoryRepository attemptRepository;
    private final Clock clock;

    @Transactional
    public DrawingRetryRequest prepare(Long drawingId, Long requestedBy) {
        memberQueryService.validateAdmin(requestedBy);
        return prepareLocked(drawingId, requestedBy);
    }

    /** 복구기는 사람 요청자가 없으므로 권한 검증 없이 동일한 잠금·상태 계약만 적용한다. */
    @Transactional
    public DrawingRetryRequest prepareRecovery(Long drawingId) {
        return prepareLocked(drawingId, null);
    }

    private DrawingRetryRequest prepareLocked(Long drawingId, Long requestedBy) {
        Drawing drawing = drawingRepository.findByIdForRetry(drawingId)
                .orElseThrow(() -> new BusinessException(DrawingErrorCode.DRAWING_NOT_FOUND));
        if (drawing.getStatus() != DrawingStatus.FAILED) {
            throw new BusinessException(drawing.getStatus() == DrawingStatus.RUNNING
                    ? DrawingErrorCode.CONCURRENT_COMMAND : DrawingErrorCode.INVALID_STATE);
        }

        attemptRepository.findFirstByDrawingIdOrderByAttemptNoDesc(drawingId)
                .filter(history -> !history.isRetryable())
                .ifPresent(history -> {
                    throw new BusinessException(DrawingErrorCode.NON_RETRYABLE_FAILURE);
                });

        drawing.retry(clock.instant());
        DrawAttemptHistory attempt = DrawAttemptHistory.started(
                drawingId, drawing.getAttemptCount(), requestedBy, clock.instant());
        attemptRepository.saveAndFlush(attempt);
        drawingRepository.flush();
        return new DrawingRetryRequest(drawingId, drawing.getAttemptCount());
    }
}
