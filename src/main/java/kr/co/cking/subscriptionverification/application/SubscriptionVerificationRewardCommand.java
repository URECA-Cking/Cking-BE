package kr.co.cking.subscriptionverification.application;

/** 구독 인증 ONCE 보상에 필요한 동결 입력이다. */
public record SubscriptionVerificationRewardCommand(
        Long verificationId,
        Long memberId,
        Long creatorId,
        Long missionId,
        String rewardRequestId,
        String rewardPeriodKey
) {

    static SubscriptionVerificationRewardCommand from(
            SubscriptionVerificationProcessingClaim claim) {
        return new SubscriptionVerificationRewardCommand(
                claim.verificationId(),
                claim.memberId(),
                claim.creatorId(),
                claim.missionId(),
                claim.rewardRequestId(),
                claim.rewardPeriodKey()
        );
    }
}
