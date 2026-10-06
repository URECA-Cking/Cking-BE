package kr.co.cking.abuse.application;

import java.time.Clock;
import kr.co.cking.abuse.application.port.AbuseDetectionRepository;
import kr.co.cking.abuse.domain.AbuseDetection;
import kr.co.cking.abuse.domain.AbuseDetectionErrorCode;
import kr.co.cking.abuse.domain.AbuseReviewDecision;
import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.member.application.MemberQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 관리자 Detection 검토의 조건부 전이와 재요청 결과를 하나의 트랜잭션으로 처리한다. */
@Service
@RequiredArgsConstructor
public class AdminAbuseDetectionReviewService {

    private final MemberQueryService memberQueryService;
    private final AbuseDetectionRepository abuseDetectionRepository;
    private final Clock clock;

    /** 관리자 권한을 확인하고 DETECTED 행만 전이하며 같은 판정은 멱등 반환한다. */
    @Transactional
    public AbuseDetection review(Long adminId, Long detectionId, AbuseReviewDecision decision) {
        memberQueryService.validateAdmin(adminId);
        int updated = abuseDetectionRepository.reviewIfDetected(
                detectionId, decision, adminId, clock.instant());
        if (updated == 1) {
            return findDetection(detectionId);
        }

        AbuseDetection detection = findDetection(detectionId);
        if (detection.status() == decision.toStatus()) {
            return detection;
        }
        throw new BusinessException(AbuseDetectionErrorCode.INVALID_STATE);
    }

    /** 조건부 UPDATE 뒤 현재 상태를 판단할 Detection을 조회하고 없으면 404로 변환한다. */
    private AbuseDetection findDetection(Long detectionId) {
        return abuseDetectionRepository.findById(detectionId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));
    }
}
