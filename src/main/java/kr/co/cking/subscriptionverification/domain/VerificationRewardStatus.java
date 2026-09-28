package kr.co.cking.subscriptionverification.domain;

/** 이미지 인증 결과와 분리해 관리하는 ONCE 응모권 보상 처리 상태다. */
public enum VerificationRewardStatus {
    NOT_REQUESTED,
    PENDING,
    ACCEPTED,
    RETRY_REQUIRED
}
