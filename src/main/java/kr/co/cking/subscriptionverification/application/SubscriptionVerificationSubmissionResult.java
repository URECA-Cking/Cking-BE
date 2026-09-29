package kr.co.cking.subscriptionverification.application;

import kr.co.cking.subscriptionverification.domain.SubscriptionVerification;

/** 신규 생성 여부를 함께 반환해 Controller가 202와 멱등 재호출 200을 구분한다. */
public record SubscriptionVerificationSubmissionResult(
        SubscriptionVerification verification,
        boolean created
) {}
