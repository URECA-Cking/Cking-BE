package kr.co.cking.subscriptionverification.application.vision;

import java.util.Objects;

/** 승인에는 사유가 없고 거절·재제출 판정에는 안정적인 사유가 필요하다. */
public record SubscriptionVerificationDecision(
        SubscriptionVerificationDecisionStatus status,
        SubscriptionVerificationDecisionReason reason) {

    public SubscriptionVerificationDecision {
        Objects.requireNonNull(status, "status");
        if (status == SubscriptionVerificationDecisionStatus.APPROVED && reason != null) {
            throw new IllegalArgumentException("승인 판정에는 사유를 지정할 수 없습니다.");
        }
        if (status != SubscriptionVerificationDecisionStatus.APPROVED && reason == null) {
            throw new IllegalArgumentException("거절 또는 재제출 판정에는 사유가 필요합니다.");
        }
    }

    public static SubscriptionVerificationDecision approved() {
        return new SubscriptionVerificationDecision(
                SubscriptionVerificationDecisionStatus.APPROVED, null);
    }

    public static SubscriptionVerificationDecision rejected(
            SubscriptionVerificationDecisionReason reason) {
        return new SubscriptionVerificationDecision(
                SubscriptionVerificationDecisionStatus.REJECTED,
                Objects.requireNonNull(reason, "reason"));
    }

    public static SubscriptionVerificationDecision retryRequired(
            SubscriptionVerificationDecisionReason reason) {
        return new SubscriptionVerificationDecision(
                SubscriptionVerificationDecisionStatus.RETRY_REQUIRED,
                Objects.requireNonNull(reason, "reason"));
    }

    public String reasonCode() {
        return reason == null ? null : reason.name();
    }
}
