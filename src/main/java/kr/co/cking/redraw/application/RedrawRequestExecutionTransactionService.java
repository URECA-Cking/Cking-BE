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

/** 승인된 RedrawRequest를 실행 트랜잭션 안에서 검증하고 정상 결과를 저장한다. */
@Service
@RequiredArgsConstructor
class RedrawRequestExecutionTransactionService {
    private final MemberQueryService memberQueryService;
    private final RedrawRequestRepository redrawRequestRepository;
    private final RedrawRequestVacancyRepository vacancyRepository;
    private final RedrawExecutionHistoryRepository historyRepository;
    private final RedrawDrawingExecutionService redrawDrawingExecutionService;

    /** 시스템3 실행 예외는 호출자까지 전파해 이 트랜잭션 전체를 롤백한다. */
    @Transactional
    public RedrawRequestExecutionResult execute(Long adminId, Long redrawRequestId) {
        memberQueryService.validateAdmin(adminId);
        RedrawRequest request = redrawRequestRepository.findByIdForUpdate(redrawRequestId)
                .orElseThrow(() -> new BusinessException(RedrawErrorCode.REDRAW_REQUEST_NOT_FOUND));
        request.validateExecutable();
        int actualVacancies = vacancyRepository.findAllByRedrawRequestIdOrderByIdAsc(redrawRequestId).size();
        if (actualVacancies != request.getVacancyCount()) {
            throw new BusinessException(RedrawErrorCode.INVALID_STATE);
        }

        RedrawDrawingExecutionResult result;
        try {
            result = redrawDrawingExecutionService.execute(
                    redrawRequestId, adminId, request.getOriginalDrawingId(), request.getVacancyCount());
        } catch (BusinessException exception) {
            throw new RedrawDrawingExecutionBusinessFailureException(exception);
        }
        if (result.insufficientCandidates()) {
            request.markInsufficientCandidates();
            historyRepository.save(RedrawExecutionHistory.of(redrawRequestId,
                    RedrawExecutionStatus.INSUFFICIENT_CANDIDATES, null, null));
            return new RedrawRequestExecutionResult(redrawRequestId, RedrawExecutionStatus.INSUFFICIENT_CANDIDATES, null);
        }
        request.markExecuted();
        historyRepository.save(RedrawExecutionHistory.of(redrawRequestId, RedrawExecutionStatus.EXECUTED, null, null));
        return new RedrawRequestExecutionResult(redrawRequestId, RedrawExecutionStatus.EXECUTED, result.drawingId());
    }
}

/** 시스템3 실행 중 발생한 업무 예외를 실행 전 요청 검증 예외와 구분한다. */
class RedrawDrawingExecutionBusinessFailureException extends RuntimeException {

    private final BusinessException businessException;

    RedrawDrawingExecutionBusinessFailureException(BusinessException businessException) {
        super(businessException);
        this.businessException = businessException;
    }

    BusinessException businessException() {
        return businessException;
    }
}
