package kr.co.cking.redraw.application;

import kr.co.cking.common.exception.BusinessException;
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

    /**
     * 업무 예외는 오류 코드를, 익명 예외는 명명된 상위 클래스를 코드로 쓰고 DB 컬럼 한계로 제한한다.
     */
    static String failureCodeOf(RuntimeException exception) {
        if (exception instanceof BusinessException businessException) {
            return truncate(businessException.getErrorCode().code());
        }
        Class<?> exceptionClass = exception.getClass();
        String name = exceptionClass.getSimpleName();
        while (name.isBlank() && exceptionClass.getSuperclass() != null) {
            exceptionClass = exceptionClass.getSuperclass();
            name = exceptionClass.getSimpleName();
        }
        if (name.isBlank()) {
            name = UNKNOWN_RUNTIME_EXCEPTION;
        }
        return truncate(name);
    }

    private static String truncate(String value) {
        return value.substring(0, Math.min(value.length(), FAILURE_CODE_MAX_LENGTH));
    }
}
