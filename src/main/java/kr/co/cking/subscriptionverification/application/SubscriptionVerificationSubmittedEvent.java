package kr.co.cking.subscriptionverification.application;

/** Commit 이후 비동기 Processor가 소비할 구독 인증 업무 이벤트다. */
public record SubscriptionVerificationSubmittedEvent(Long verificationId) {}
