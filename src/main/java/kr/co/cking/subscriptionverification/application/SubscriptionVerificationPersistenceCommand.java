package kr.co.cking.subscriptionverification.application;

record SubscriptionVerificationPersistenceCommand(
        Long memberId,
        Long creatorId,
        Long missionId,
        String requestId,
        String requestFingerprint,
        String imageObjectKey,
        String imageSha256,
        String normalizationVersion,
        String rewardRequestId
) {}
