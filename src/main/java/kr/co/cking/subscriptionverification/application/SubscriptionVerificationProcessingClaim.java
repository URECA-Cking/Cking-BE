package kr.co.cking.subscriptionverification.application;

import java.time.Instant;

/** 외부 처리에 필요한 동결 입력과 DB가 부여한 fencing token이다. */
public record SubscriptionVerificationProcessingClaim(
        Long verificationId,
        String processingToken,
        Long memberId,
        Long creatorId,
        Long missionId,
        String imageObjectKey,
        String targetChannelName,
        String targetChannelHandle,
        String rewardRequestId,
        String rewardPeriodKey,
        int attemptCount,
        Instant processingLeaseUntil
) {}
