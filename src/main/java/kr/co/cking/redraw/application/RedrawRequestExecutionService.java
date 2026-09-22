package kr.co.cking.redraw.application;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.drawing.application.RedrawDrawingExecutionResult;
import kr.co.cking.drawing.application.RedrawDrawingExecutionService;
import kr.co.cking.redraw.domain.RedrawExecutionStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** REDRAW 실행 결과를 조정하고, 시스템3 실행 단계의 실패는 독립 트랜잭션으로 기록한다. */
@Service
@RequiredArgsConstructor
public class RedrawRequestExecutionService {
    private final RedrawRequestExecutionTransactionService executionTransactionService;
    private final RedrawRequestFailurePersistenceService failurePersistenceService;

    /** 실행 Tx가 롤백된 뒤 시스템3 실행 실패 상태와 이력을 별도 Tx에 확정한다. */
    public RedrawRequestExecutionResult execute(Long adminId, Long redrawRequestId) {
        try {
            return executionTransactionService.execute(adminId, redrawRequestId);
        } catch (BusinessException exception) {
            throw exception;
        } catch (RedrawDrawingExecutionBusinessFailureException exception) {
            failurePersistenceService.recordFailure(redrawRequestId, exception.businessException());
            return new RedrawRequestExecutionResult(redrawRequestId, RedrawExecutionStatus.FAILED, null);
        } catch (RuntimeException exception) {
            failurePersistenceService.recordFailure(redrawRequestId, exception);
            return new RedrawRequestExecutionResult(redrawRequestId, RedrawExecutionStatus.FAILED, null);
        }
    }
}
