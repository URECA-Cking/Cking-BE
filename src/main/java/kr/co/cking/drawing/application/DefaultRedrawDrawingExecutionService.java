package kr.co.cking.drawing.application;

import kr.co.cking.drawing.domain.DrawingStatus;
import kr.co.cking.drawing.repository.DrawingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** REDRAW 준비를 먼저 확정한 뒤 공통 Drawing 실행·실패 보존 경로에 위임한다. */
@Service
@RequiredArgsConstructor
public class DefaultRedrawDrawingExecutionService implements RedrawDrawingExecutionService {

    private final RedrawDrawingPreparationService preparationService;
    private final DrawingRetryService retryService;
    private final DrawingRepository drawingRepository;

    @Override
    public RedrawDrawingExecutionResult execute(
            Long requestId,
            Long adminId,
            Long originalDrawingId,
            int vacancyCount
    ) {
        RedrawDrawingPreparation preparation = preparationService.prepare(
                requestId, adminId, originalDrawingId, vacancyCount);
        if (!preparation.requiresExecution()) {
            return preparation.terminalResult();
        }

        Long drawingId = preparation.executionRequest().drawingId();
        try {
            DrawingRetryResult result = retryService.executePrepared(preparation.executionRequest());
            return RedrawDrawingExecutionResult.executed(result.drawingId());
        } catch (RuntimeException failure) {
            // 실패 상태 보존까지 성공한 경우에만 업무상 FAILED 결과로 변환한다.
            if (drawingRepository.findById(drawingId)
                    .filter(drawing -> drawing.getStatus() == DrawingStatus.FAILED)
                    .isPresent()) {
                return RedrawDrawingExecutionResult.failed(drawingId);
            }
            throw failure;
        }
    }
}
