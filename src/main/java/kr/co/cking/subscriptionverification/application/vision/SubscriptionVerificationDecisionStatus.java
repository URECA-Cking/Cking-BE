package kr.co.cking.subscriptionverification.application.vision;

/** VLM 관측 결과에 대한 서버의 최종 업무 판정이다. */
public enum SubscriptionVerificationDecisionStatus {
    APPROVED,
    REJECTED,
    RETRY_REQUIRED
}
