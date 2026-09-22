package kr.co.cking.redraw.application;

import java.time.Instant;
import kr.co.cking.redraw.domain.RedrawErrorCode;
import kr.co.cking.redraw.domain.RedrawExecutionHistory;
import kr.co.cking.redraw.domain.RedrawExecutionStatus;
import kr.co.cking.redraw.domain.RedrawRequest;
import kr.co.cking.redraw.repository.RedrawExecutionHistoryRepository;
import kr.co.cking.redraw.repository.RedrawRequestRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Drawing 결과 Transaction 안에서 RedrawRequest 상태와 실행 이력을 함께 확정하는 도메인 경계다. */
@Service
@RequiredArgsConstructor
public class RedrawDrawingLifecycleService {

    private final RedrawRequestRepository requestRepository;
    private final RedrawExecutionHistoryRepository historyRepository;

    @Transactional(propagation = Propagation.MANDATORY)
    public void complete(Long redrawRequestId, Instant completedAt) {
        RedrawRequest request = findForUpdate(redrawRequestId);
        request.completeExecution(completedAt);
        historyRepository.save(RedrawExecutionHistory.of(
                redrawRequestId, RedrawExecutionStatus.EXECUTED, null, null));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void fail(
            Long redrawRequestId,
            Instant failedAt,
            String failureCode,
            String failureMessage
    ) {
        RedrawRequest request = findForUpdate(redrawRequestId);
        request.failExecution(failedAt);
        historyRepository.save(RedrawExecutionHistory.of(
                redrawRequestId,
                RedrawExecutionStatus.FAILED,
                truncateCode(failureCode),
                failureMessage));
    }

    private RedrawRequest findForUpdate(Long redrawRequestId) {
        return requestRepository.findByIdForUpdate(redrawRequestId)
                .orElseThrow(() -> new IllegalStateException(
                        RedrawErrorCode.REDRAW_REQUEST_NOT_FOUND.code()));
    }

    private String truncateCode(String code) {
        if (code == null) {
            return null;
        }
        return code.length() <= 50 ? code : code.substring(0, 50);
    }
}
