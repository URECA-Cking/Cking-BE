package kr.co.cking.drawing.application;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** 관리자 Retry와 서버 중단 복구가 공유하는 비트랜잭션 조정 경계다. */
@Service
@RequiredArgsConstructor
public class DrawingRetryService {

    private final DrawingRetryPreparationService preparationService;
    private final DrawingRetryExecutionService executionService;
    private final DrawingFailurePersistenceService failurePersistenceService;

    public DrawingRetryResult retry(Long drawingId, Long requestedBy) {
        return execute(preparationService.prepare(drawingId, requestedBy));
    }

    public DrawingRetryResult recover(Long drawingId) {
        return execute(preparationService.prepareRecovery(drawingId));
    }

    private DrawingRetryResult execute(DrawingRetryRequest request) {
        try {
            return executionService.execute(request);
        } catch (DrawingExecutionFailure failure) {
            failurePersistenceService.recordFailure(request, failure.stage(), failure.failureCode(),
                    failure.getMessage());
            throw failure.original();
        } catch (RuntimeException failure) {
            failurePersistenceService.recordFailure(request,
                    kr.co.cking.drawing.domain.DrawingFailureStage.RESULT_PERSISTENCE,
                    "SYSTEM_ERROR", failure.getMessage());
            throw failure;
        }
    }
}
