package kr.co.cking.subscriptionverification.presentation.dto;

import java.time.Instant;
import kr.co.cking.subscriptionverification.application.SubscriptionVerificationPublicStatus;
import kr.co.cking.subscriptionverification.domain.SubscriptionVerification;
import kr.co.cking.subscriptionverification.domain.VerificationRewardStatus;

public record SubscriptionVerificationSubmissionResponse(
        Long verificationId,
        SubscriptionVerificationPublicStatus status,
        boolean rewarded,
        Instant submittedAt
) {
    public static SubscriptionVerificationSubmissionResponse from(
            SubscriptionVerification verification
    ) {
        return new SubscriptionVerificationSubmissionResponse(
                verification.getVerificationId(),
                SubscriptionVerificationPublicStatus.from(
                        verification.getStatus(), verification.getRewardStatus()),
                verification.getRewardStatus() == VerificationRewardStatus.ACCEPTED,
                verification.getCreatedAt());
    }
}
