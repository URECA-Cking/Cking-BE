package kr.co.cking.subscriptionverification.application;

import kr.co.cking.subscriptionverification.domain.SubscriptionVerificationStatus;
import kr.co.cking.subscriptionverification.domain.VerificationRewardStatus;

/** 내부 처리 상태와 보상 상태를 사용자에게 노출할 공개 상태로 변환한다. */
public enum SubscriptionVerificationPublicStatus {
    VERIFYING,
    VERIFIED,
    REJECTED,
    RETRY_REQUIRED,
    TEMPORARY_ERROR;

    public static SubscriptionVerificationPublicStatus from(
            SubscriptionVerificationStatus status,
            VerificationRewardStatus rewardStatus
    ) {
        return switch (status) {
            case PENDING, PROCESSING -> VERIFYING;
            case APPROVED -> switch (rewardStatus) {
                case ACCEPTED -> VERIFIED;
                case RETRY_REQUIRED -> TEMPORARY_ERROR;
                case NOT_REQUESTED, PENDING -> VERIFYING;
            };
            case REJECTED -> REJECTED;
            case RETRY_REQUIRED -> RETRY_REQUIRED;
            case FAILED -> TEMPORARY_ERROR;
        };
    }
}
