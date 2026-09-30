package kr.co.cking.subscriptionverification.application;

/** Scheduler가 Transaction 밖에서 처리할 구독 인증 복구 입력이다. */
public record SubscriptionVerificationRecoveryTarget(
        Long verificationId,
        SubscriptionVerificationRewardCommand rewardCommand,
        int rewardAttemptCount
) {

    public static SubscriptionVerificationRecoveryTarget reward(
            SubscriptionVerificationRewardCommand rewardCommand,
            int rewardAttemptCount) {
        return new SubscriptionVerificationRecoveryTarget(
                rewardCommand.verificationId(), rewardCommand, rewardAttemptCount);
    }
}
