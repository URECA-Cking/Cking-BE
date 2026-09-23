package kr.co.cking.redraw.application;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.drawing.application.RedrawDrawingExecutionResult;
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

/** REDRAW 요청 검증과 후보 부족 종결을 짧은 Transaction으로 처리한다. */
@Service
@RequiredArgsConstructor
class RedrawRequestExecutionTransactionService {

    private final MemberQueryService memberQueryService;
    private final RedrawRequestRepository redrawRequestRepository;
    private final RedrawRequestVacancyRepository vacancyRepository;
    private final RedrawExecutionHistoryRepository historyRepository;

    /** 장시간 실행 전에 관리자·요청 상태·고정 결원을 검증하고 Transaction 잠금을 해제한다. */
    @Transactional(readOnly = true)
    public RedrawExecutionCommand prepare(Long adminId, Long redrawRequestId) {
        memberQueryService.validateAdmin(adminId);
        RedrawRequest request = redrawRequestRepository.findById(redrawRequestId)
                .orElseThrow(() -> new BusinessException(RedrawErrorCode.REDRAW_REQUEST_NOT_FOUND));
        request.validateExecutable();
        int actualVacancies = vacancyRepository
                .findAllByRedrawRequestIdOrderByIdAsc(redrawRequestId)
                .size();
        if (actualVacancies != request.getVacancyCount()) {
            throw new BusinessException(RedrawErrorCode.INVALID_STATE);
        }
        return new RedrawExecutionCommand(
                redrawRequestId, adminId, request.getOriginalDrawingId(), request.getVacancyCount());
    }

    /** 시스템3 종결 결과를 멱등하게 RedrawRequest와 감사 이력에 반영한다. */
    @Transactional
    public RedrawRequestExecutionResult complete(
            RedrawExecutionCommand command,
            RedrawDrawingExecutionResult result
    ) {
        RedrawRequest request = redrawRequestRepository.findByIdForUpdate(command.redrawRequestId())
                .orElseThrow(() -> new BusinessException(RedrawErrorCode.REDRAW_REQUEST_NOT_FOUND));
        if (result.failed()) {
            if (request.getExecutionStatus() != RedrawExecutionStatus.FAILED) {
                throw new BusinessException(RedrawErrorCode.INVALID_STATE);
            }
            return new RedrawRequestExecutionResult(
                    command.redrawRequestId(), RedrawExecutionStatus.FAILED, result.drawingId());
        }
        if (result.insufficientCandidates()) {
            if (request.getExecutionStatus() == RedrawExecutionStatus.PENDING) {
                request.markInsufficientCandidates();
                historyRepository.save(RedrawExecutionHistory.of(
                        command.redrawRequestId(), RedrawExecutionStatus.INSUFFICIENT_CANDIDATES, null, null));
            } else if (request.getExecutionStatus() != RedrawExecutionStatus.INSUFFICIENT_CANDIDATES) {
                throw new BusinessException(RedrawErrorCode.INVALID_STATE);
            }
            return new RedrawRequestExecutionResult(
                    command.redrawRequestId(), RedrawExecutionStatus.INSUFFICIENT_CANDIDATES, null);
        }

        // 실제 구현은 Drawing 결과 Transaction 안에서 이미 EXECUTED로 바꾼다.
        // 대체 구현·테스트 Double은 이 경계에서 같은 계약을 완성한다.
        if (request.getExecutionStatus() == RedrawExecutionStatus.PENDING) {
            request.markExecuted();
            historyRepository.save(RedrawExecutionHistory.of(
                    command.redrawRequestId(), RedrawExecutionStatus.EXECUTED, null, null));
        } else if (request.getExecutionStatus() != RedrawExecutionStatus.EXECUTED) {
            throw new BusinessException(RedrawErrorCode.INVALID_STATE);
        }
        return new RedrawRequestExecutionResult(
                command.redrawRequestId(), RedrawExecutionStatus.EXECUTED, result.drawingId());
    }
}
