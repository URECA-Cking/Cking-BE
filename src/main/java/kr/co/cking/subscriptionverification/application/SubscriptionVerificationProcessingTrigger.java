package kr.co.cking.subscriptionverification.application;

/** Commit된 PENDING Verification을 향후 bounded executor에 전달할 내부 경계다. */
@FunctionalInterface
public interface SubscriptionVerificationProcessingTrigger {

    /** Commit된 Verification 식별자만 전용 비동기 처리 경계에 전달한다. */
    void trigger(Long verificationId);
}
