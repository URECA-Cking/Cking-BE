package kr.co.cking.subscriptionverification.application;

import kr.co.cking.subscriptionverification.domain.SubscriptionVerificationStatus;

/** Processing 소유자가 저장할 수 있는 종료 결과다. */
public enum SubscriptionVerificationProcessingOutcome {
    APPROVED(SubscriptionVerificationStatus.APPROVED),
    REJECTED(SubscriptionVerificationStatus.REJECTED),
    RETRY_REQUIRED(SubscriptionVerificationStatus.RETRY_REQUIRED),
    FAILED(SubscriptionVerificationStatus.FAILED);

    private final SubscriptionVerificationStatus status;

    SubscriptionVerificationProcessingOutcome(SubscriptionVerificationStatus status) {
        this.status = status;
    }

    SubscriptionVerificationStatus status() {
        return status;
    }
}
