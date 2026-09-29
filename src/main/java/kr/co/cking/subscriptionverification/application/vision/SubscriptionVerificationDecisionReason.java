package kr.co.cking.subscriptionverification.application.vision;

/** DB와 사용자 응답에 사용할 수 있는 안정적인 서버 판정 사유다. */
public enum SubscriptionVerificationDecisionReason {
    PLATFORM_MISMATCH,
    CHANNEL_MISMATCH,
    NOT_SUBSCRIBED,
    INSUFFICIENT_EVIDENCE,
    LOW_CONFIDENCE
}
