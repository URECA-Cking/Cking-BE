package kr.co.cking.subscriptionverification.domain;

/** 구독 인증의 내부 처리 상태다. 외부 API에는 별도의 공개 상태로 변환한다. */
public enum SubscriptionVerificationStatus {
    PENDING,
    PROCESSING,
    APPROVED,
    REJECTED,
    RETRY_REQUIRED,
    FAILED
}
