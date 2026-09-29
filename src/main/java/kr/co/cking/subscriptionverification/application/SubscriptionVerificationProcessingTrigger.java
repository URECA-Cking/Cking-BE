package kr.co.cking.subscriptionverification.application;

/** Commit된 PENDING Verification을 향후 bounded executor에 전달할 내부 경계다. */
@FunctionalInterface
public interface SubscriptionVerificationProcessingTrigger {

    void trigger(Long verificationId);
}
