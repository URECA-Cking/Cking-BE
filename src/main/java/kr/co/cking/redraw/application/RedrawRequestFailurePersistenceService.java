package kr.co.cking.redraw.application;

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

/** 롤백된 REDRAW 실행과 분리해 실패 상태와 감사 이력을 확정한다. */
@Service
@RequiredArgsConstructor
class RedrawRequestFailurePersistenceService {
    private final RedrawRequestRepository redrawRequestRepository;
    private final RedrawExecutionHistoryRepository historyRepository;

    /** 실행 트랜잭션의 rollback-only 상태와 무관하게 실패 결과를 새 트랜잭션으로 저장한다. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailure(Long redrawRequestId, RuntimeException exception) {
        RedrawRequest request = redrawRequestRepository.findByIdForUpdate(redrawRequestId)
                .orElseThrow(() -> new IllegalStateException(RedrawErrorCode.REDRAW_REQUEST_NOT_FOUND.code()));
        request.markFailed();
        historyRepository.save(RedrawExecutionHistory.of(redrawRequestId, RedrawExecutionStatus.FAILED,
                exception.getClass().getSimpleName(), exception.getMessage()));
    }
}
