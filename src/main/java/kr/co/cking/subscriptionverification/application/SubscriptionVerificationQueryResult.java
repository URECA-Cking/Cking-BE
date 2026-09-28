package kr.co.cking.subscriptionverification.application;

import java.time.Instant;
import kr.co.cking.subscriptionverification.domain.SubscriptionVerification;
import kr.co.cking.subscriptionverification.domain.VerificationRewardStatus;

/** 구독 인증 조회 API가 노출하는 공개 상태와 최소 결과 정보다. */
public record SubscriptionVerificationQueryResult(
        Long verificationId,
        SubscriptionVerificationPublicStatus status,
        boolean rewarded,
        String reasonCode,
        Instant submittedAt,
        Instant processedAt
) {
    public static SubscriptionVerificationQueryResult from(SubscriptionVerification verification) {
        return new SubscriptionVerificationQueryResult(
                verification.getVerificationId(),
                SubscriptionVerificationPublicStatus.from(
                        verification.getStatus(), verification.getRewardStatus()),
                verification.getRewardStatus() == VerificationRewardStatus.ACCEPTED,
                verification.getReasonCode(),
                verification.getCreatedAt(),
                verification.getProcessedAt()
        );
    }
}
