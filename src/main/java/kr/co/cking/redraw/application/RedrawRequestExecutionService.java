package kr.co.cking.redraw.application;

import kr.co.cking.drawing.application.RedrawDrawingExecutionResult;
import kr.co.cking.drawing.application.RedrawDrawingExecutionService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** 짧은 요청 검증과 시스템3 실행, 최종 상태 반영을 Transaction 경계별로 조정한다. */
@Service
@RequiredArgsConstructor
public class RedrawRequestExecutionService {
    private final RedrawRequestExecutionTransactionService executionTransactionService;
    private final RedrawDrawingExecutionService redrawDrawingExecutionService;

    /**
     * Drawing 준비 전 오류는 요청을 PENDING으로 남겨 같은 실행 API로 재시도한다. 보존된 Drawing의 실행 실패만
     * 시스템3이 FAILED 결과와 drawingId로 종결한다.
     */
    public RedrawRequestExecutionResult execute(Long adminId, Long redrawRequestId) {
        RedrawExecutionCommand command = executionTransactionService.prepare(adminId, redrawRequestId);
        RedrawDrawingExecutionResult drawingResult = redrawDrawingExecutionService.execute(
                command.redrawRequestId(),
                command.adminId(),
                command.originalDrawingId(),
                command.vacancyCount());
        return executionTransactionService.complete(command, drawingResult);
    }
}
