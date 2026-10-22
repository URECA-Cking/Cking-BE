package kr.co.cking.subscriptionverification.application;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.member.application.MemberQueryService;
import kr.co.cking.subscriptionverification.domain.SubscriptionVerification;
import kr.co.cking.subscriptionverification.domain.SubscriptionVerificationErrorCode;
import kr.co.cking.subscriptionverification.repository.SubscriptionVerificationRepository;
import kr.co.cking.subscriptionverification.repository.SubscriptionVerificationImageReuseRepository;
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
    private final SubscriptionVerificationImageReuseRepository imageReuseRepository;

    /** 인증된 사용자가 자신이 제출한 구독 인증의 공개 처리 상태를 조회한다. */
    public SubscriptionVerificationQueryResult getMine(Long memberId, Long verificationId) {
        memberQueryService.validateExists(memberId);
        SubscriptionVerification verification = findVerification(verificationId);
        if (!verification.getMemberId().equals(memberId)) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN);
        }
        return SubscriptionVerificationQueryResult.from(verification);
    }

    /** 인증된 사용자가 Creator 미션에 제출한 가장 최근 구독 인증 상태를 조회한다. */
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

    /** 관리자가 Verification의 이미지 재사용 탐지 감사 결과를 조회한다. */
    public SubscriptionVerificationImageReuseQueryResult getImageReuseForAdmin(
            Long adminId,
            Long verificationId
    ) {
        memberQueryService.validateAdmin(adminId);
        findVerification(verificationId);
        return imageReuseRepository.findById(verificationId)
                .map(SubscriptionVerificationImageReuseQueryResult::from)
                .orElseThrow(() -> new BusinessException(
                        SubscriptionVerificationErrorCode.VERIFICATION_NOT_FOUND));
    }

    /** Verification 식별자로 존재하는 구독 인증을 조회한다. */
    private SubscriptionVerification findVerification(Long verificationId) {
        return verificationRepository.findById(verificationId)
                .orElseThrow(() -> new BusinessException(
                        SubscriptionVerificationErrorCode.VERIFICATION_NOT_FOUND));
    }
}
