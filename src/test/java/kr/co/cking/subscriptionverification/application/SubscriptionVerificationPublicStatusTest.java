package kr.co.cking.subscriptionverification.application;

import static org.assertj.core.api.Assertions.assertThat;

import kr.co.cking.subscriptionverification.domain.SubscriptionVerificationStatus;
import kr.co.cking.subscriptionverification.domain.VerificationRewardStatus;
import org.junit.jupiter.api.Test;

class SubscriptionVerificationPublicStatusTest {

    @Test
    void 처리_중인_상태는_VERIFYING이다() {
        assertThat(status(SubscriptionVerificationStatus.PENDING, VerificationRewardStatus.NOT_REQUESTED))
                .isEqualTo(SubscriptionVerificationPublicStatus.VERIFYING);
        assertThat(status(SubscriptionVerificationStatus.PROCESSING, VerificationRewardStatus.NOT_REQUESTED))
                .isEqualTo(SubscriptionVerificationPublicStatus.VERIFYING);
    }

    @Test
    void 승인_상태는_보상_상태에_따라_공개한다() {
        assertThat(status(SubscriptionVerificationStatus.APPROVED, VerificationRewardStatus.NOT_REQUESTED))
                .isEqualTo(SubscriptionVerificationPublicStatus.VERIFYING);
        assertThat(status(SubscriptionVerificationStatus.APPROVED, VerificationRewardStatus.PENDING))
                .isEqualTo(SubscriptionVerificationPublicStatus.VERIFYING);
        assertThat(status(SubscriptionVerificationStatus.APPROVED, VerificationRewardStatus.ACCEPTED))
                .isEqualTo(SubscriptionVerificationPublicStatus.VERIFIED);
        assertThat(status(SubscriptionVerificationStatus.APPROVED, VerificationRewardStatus.RETRY_REQUIRED))
                .isEqualTo(SubscriptionVerificationPublicStatus.TEMPORARY_ERROR);
    }

    @Test
    void 비승인_종료_상태는_업무_의미에_맞춰_공개한다() {
        assertThat(status(SubscriptionVerificationStatus.REJECTED, VerificationRewardStatus.NOT_REQUESTED))
                .isEqualTo(SubscriptionVerificationPublicStatus.REJECTED);
        assertThat(status(SubscriptionVerificationStatus.RETRY_REQUIRED, VerificationRewardStatus.NOT_REQUESTED))
                .isEqualTo(SubscriptionVerificationPublicStatus.RETRY_REQUIRED);
        assertThat(status(SubscriptionVerificationStatus.FAILED, VerificationRewardStatus.NOT_REQUESTED))
                .isEqualTo(SubscriptionVerificationPublicStatus.TEMPORARY_ERROR);
    }

    private SubscriptionVerificationPublicStatus status(
            SubscriptionVerificationStatus status,
            VerificationRewardStatus rewardStatus
    ) {
        return SubscriptionVerificationPublicStatus.from(status, rewardStatus);
    }
}
