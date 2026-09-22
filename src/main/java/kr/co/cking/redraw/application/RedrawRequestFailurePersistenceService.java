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
    private static final int FAILURE_CODE_MAX_LENGTH = 50;
    private static final String UNKNOWN_RUNTIME_EXCEPTION = "UNKNOWN_RUNTIME_EXCEPTION";

    private final RedrawRequestRepository redrawRequestRepository;
    private final RedrawExecutionHistoryRepository historyRepository;

    /** 실행 트랜잭션의 rollback-only 상태와 무관하게 실패 결과를 새 트랜잭션으로 저장한다. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailure(Long redrawRequestId, RuntimeException exception) {
        RedrawRequest request = redrawRequestRepository.findByIdForUpdate(redrawRequestId)
                .orElseThrow(() -> new IllegalStateException(RedrawErrorCode.REDRAW_REQUEST_NOT_FOUND.code()));
        request.markFailed();
        historyRepository.save(RedrawExecutionHistory.of(redrawRequestId, RedrawExecutionStatus.FAILED,
                failureCodeOf(exception), exception.getMessage()));
    }

    /** 감사 이력 저장이 예외 클래스명 길이 때문에 실패하지 않도록 DB 컬럼 한계로 제한한다. */
    private static String failureCodeOf(RuntimeException exception) {
        String name = exception.getClass().getSimpleName();
        if (name.isBlank()) {
            Class<?> superclass = exception.getClass().getSuperclass();
            name = superclass == null ? UNKNOWN_RUNTIME_EXCEPTION : superclass.getSimpleName();
        }
        if (name.isBlank()) {
            name = UNKNOWN_RUNTIME_EXCEPTION;
        }
        return name.substring(0, Math.min(name.length(), FAILURE_CODE_MAX_LENGTH));
    }
}
