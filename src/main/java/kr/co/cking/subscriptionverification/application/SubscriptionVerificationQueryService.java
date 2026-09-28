package kr.co.cking.subscriptionverification.application;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.member.application.MemberQueryService;
import kr.co.cking.subscriptionverification.domain.SubscriptionVerification;
import kr.co.cking.subscriptionverification.domain.SubscriptionVerificationErrorCode;
import kr.co.cking.subscriptionverification.repository.SubscriptionVerificationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 인증된 사용자의 구독 인증 상태를 읽기 전용으로 조회한다. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SubscriptionVerificationQueryService {

    private final MemberQueryService memberQueryService;
    private final SubscriptionVerificationRepository verificationRepository;

    public SubscriptionVerificationQueryResult getMine(Long memberId, Long verificationId) {
        memberQueryService.validateExists(memberId);
        SubscriptionVerification verification = findVerification(verificationId);
        if (!verification.getMemberId().equals(memberId)) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN);
        }
        return SubscriptionVerificationQueryResult.from(verification);
    }

    public SubscriptionVerificationQueryResult getLatestMine(
            Long memberId,
            Long creatorId,
            Long missionId
    ) {
        memberQueryService.validateExists(memberId);
        SubscriptionVerification verification = verificationRepository
                .findFirstByMemberIdAndCreatorIdAndMissionIdOrderByCreatedAtDescVerificationIdDesc(
                        memberId, creatorId, missionId)
                .orElseThrow(() -> new BusinessException(
                        SubscriptionVerificationErrorCode.VERIFICATION_NOT_FOUND));
        return SubscriptionVerificationQueryResult.from(verification);
    }

    private SubscriptionVerification findVerification(Long verificationId) {
        return verificationRepository.findById(verificationId)
                .orElseThrow(() -> new BusinessException(
                        SubscriptionVerificationErrorCode.VERIFICATION_NOT_FOUND));
    }
}
