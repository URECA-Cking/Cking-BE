package kr.co.cking.redraw.application;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.member.application.MemberQueryService;
import kr.co.cking.redraw.domain.RedrawErrorCode;
import kr.co.cking.redraw.domain.RedrawRequest;
import kr.co.cking.redraw.repository.RedrawRequestRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 관리자 권한을 확인하고 RedrawRequest의 승인·거절 심사 상태 전이를 조정한다. */
@Service
@RequiredArgsConstructor
@Transactional
public class RedrawRequestReviewService {

    private final MemberQueryService memberQueryService;
    private final RedrawRequestRepository redrawRequestRepository;

    /** 관리자가 검토 대기 RedrawRequest를 승인하고 심사 결과를 반환한다. */
    public RedrawRequestReviewResult approve(Long adminId, Long redrawRequestId) {
        memberQueryService.validateAdmin(adminId);
        RedrawRequest request = findForReview(redrawRequestId);
        request.approve(adminId);
        return RedrawRequestReviewResult.from(request);
    }

    /** 관리자가 검토 대기 RedrawRequest를 거절하고 사유를 포함한 심사 결과를 반환한다. */
    public RedrawRequestReviewResult reject(Long adminId, Long redrawRequestId, String rejectReason) {
        String normalizedRejectReason = validateAndNormalizeRejectReason(rejectReason);
        memberQueryService.validateAdmin(adminId);
        RedrawRequest request = findForReview(redrawRequestId);
        request.reject(adminId, normalizedRejectReason);
        return RedrawRequestReviewResult.from(request);
    }

    /** 잠금 획득 전에 거절 사유를 정규화하고 1~500자 범위인지 검증한다. */
    private String validateAndNormalizeRejectReason(String rejectReason) {
        String normalizedRejectReason = rejectReason == null ? null : rejectReason.strip();
        if (normalizedRejectReason == null || normalizedRejectReason.isEmpty() || normalizedRejectReason.length() > 500) {
            throw new BusinessException(CommonErrorCode.VALIDATION_FAILED);
        }
        return normalizedRejectReason;
    }

    /** 동시 심사를 직렬화할 잠금으로 대상 RedrawRequest를 조회한다. */
    private RedrawRequest findForReview(Long redrawRequestId) {
        return redrawRequestRepository.findByIdForUpdate(redrawRequestId)
                .orElseThrow(() -> new BusinessException(RedrawErrorCode.REDRAW_REQUEST_NOT_FOUND));
    }
}
