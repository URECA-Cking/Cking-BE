package kr.co.cking.redraw.application;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.drawing.application.RedrawDrawingExecutionResult;
import kr.co.cking.drawing.application.RedrawDrawingExecutionService;
import kr.co.cking.member.application.MemberQueryService;
import kr.co.cking.redraw.domain.RedrawErrorCode;
import kr.co.cking.redraw.domain.RedrawExecutionHistory;
import kr.co.cking.redraw.domain.RedrawExecutionStatus;
import kr.co.cking.redraw.domain.RedrawRequest;
import kr.co.cking.redraw.repository.RedrawExecutionHistoryRepository;
import kr.co.cking.redraw.repository.RedrawRequestRepository;
import kr.co.cking.redraw.repository.RedrawRequestVacancyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 승인된 RedrawRequest의 검증·상태 이력만 담당하고 실제 추첨은 시스템3에 위임한다. */
@Service
@RequiredArgsConstructor
public class RedrawRequestExecutionService {
    private final MemberQueryService memberQueryService;
    private final RedrawRequestRepository redrawRequestRepository;
    private final RedrawRequestVacancyRepository vacancyRepository;
    private final RedrawExecutionHistoryRepository historyRepository;
    private final RedrawDrawingExecutionService redrawDrawingExecutionService;

    /** 관리자 권한과 고정 결원을 검증한 뒤 시스템3 REDRAW 실행 결과를 요청 상태에 반영한다. */
    @Transactional
    public RedrawRequestExecutionResult execute(Long adminId, Long redrawRequestId) {
        memberQueryService.validateAdmin(adminId);
        RedrawRequest request = redrawRequestRepository.findByIdForUpdate(redrawRequestId)
                .orElseThrow(() -> new BusinessException(RedrawErrorCode.REDRAW_REQUEST_NOT_FOUND));
        int actualVacancies = vacancyRepository.findAllByRedrawRequestIdOrderByIdAsc(redrawRequestId).size();
        if (actualVacancies != request.getVacancyCount()) {
            throw new BusinessException(RedrawErrorCode.INVALID_STATE);
        }
        try {
            RedrawDrawingExecutionResult result = redrawDrawingExecutionService.execute(
                    redrawRequestId, adminId, request.getOriginalDrawingId(), request.getVacancyCount());
            if (result.insufficientCandidates()) {
                request.markInsufficientCandidates();
                historyRepository.save(RedrawExecutionHistory.of(redrawRequestId,
                        RedrawExecutionStatus.INSUFFICIENT_CANDIDATES, null, null));
                return new RedrawRequestExecutionResult(redrawRequestId, RedrawExecutionStatus.INSUFFICIENT_CANDIDATES, null);
            }
            request.markExecuted();
            historyRepository.save(RedrawExecutionHistory.of(redrawRequestId, RedrawExecutionStatus.EXECUTED, null, null));
            return new RedrawRequestExecutionResult(redrawRequestId, RedrawExecutionStatus.EXECUTED, result.drawingId());
        } catch (BusinessException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            request.markFailed();
            historyRepository.save(RedrawExecutionHistory.of(redrawRequestId, RedrawExecutionStatus.FAILED,
                    exception.getClass().getSimpleName(), exception.getMessage()));
            return new RedrawRequestExecutionResult(redrawRequestId, RedrawExecutionStatus.FAILED, null);
        }
    }
}
