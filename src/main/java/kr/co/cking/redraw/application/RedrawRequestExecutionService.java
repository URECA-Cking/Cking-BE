package kr.co.cking.redraw.application;

import kr.co.cking.drawing.application.RedrawDrawingExecutionResult;
import kr.co.cking.drawing.application.RedrawDrawingExecutionService;
import kr.co.cking.redraw.domain.RedrawExecutionStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** 짧은 요청 검증과 시스템3 실행, 최종 상태 반영을 Transaction 경계별로 조정한다. */
@Service
@RequiredArgsConstructor
public class RedrawRequestExecutionService {
    private final RedrawRequestExecutionTransactionService executionTransactionService;
    private final RedrawRequestFailurePersistenceService failurePersistenceService;
    private final RedrawDrawingExecutionService redrawDrawingExecutionService;

    /** 준비 전 실패는 별도 실패 경계에 기록하고, 보존된 Drawing 실패는 시스템3 결과를 그대로 반환한다. */
    public RedrawRequestExecutionResult execute(Long adminId, Long redrawRequestId) {
        RedrawExecutionCommand command = executionTransactionService.prepare(adminId, redrawRequestId);
        RedrawDrawingExecutionResult drawingResult;
        try {
            drawingResult = redrawDrawingExecutionService.execute(
                    command.redrawRequestId(),
                    command.adminId(),
                    command.originalDrawingId(),
                    command.vacancyCount());
        } catch (RuntimeException exception) {
            failurePersistenceService.recordFailure(redrawRequestId, exception);
            return new RedrawRequestExecutionResult(redrawRequestId, RedrawExecutionStatus.FAILED, null);
        }
        return executionTransactionService.complete(command, drawingResult);
    }
}
