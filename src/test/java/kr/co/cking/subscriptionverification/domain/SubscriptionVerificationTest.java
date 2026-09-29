package kr.co.cking.subscriptionverification.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class SubscriptionVerificationTest {

    private static final Instant CREATED_AT = Instant.parse("2026-09-28T23:59:59Z");
    private static final String REQUEST_ID = "10000000-0000-4000-8000-000000000001";
    private static final String REWARD_REQUEST_ID = "20000000-0000-4000-8000-000000000001";
    private static final String TOKEN = "30000000-0000-4000-8000-000000000001";

    @Test
    void 새_인증은_멱등성과_보상_기준을_생성_시점에_동결한다() {
        SubscriptionVerification verification = newVerification();

        assertThat(verification.getStatus()).isEqualTo(SubscriptionVerificationStatus.PENDING);
        assertThat(verification.getRewardStatus()).isEqualTo(VerificationRewardStatus.NOT_REQUESTED);
        assertThat(verification.getActiveGuard()).isEqualTo((byte) 1);
        assertThat(verification.getApprovedGuard()).isNull();
        assertThat(verification.getRewardRequestId()).isEqualTo(REWARD_REQUEST_ID);
        assertThat(verification.getRewardPeriodKey()).isEqualTo("2026-09-28");
        assertThat(verification.getAttemptCount()).isZero();
    }

    @Test
    void UTC_자정_이후_생성하면_그_날짜를_보상_periodKey로_고정한다() {
        SubscriptionVerification verification = pendingAt(Instant.parse("2026-09-29T00:00:00Z"));

        assertThat(verification.getRewardPeriodKey()).isEqualTo("2026-09-29");
    }

    @Test
    void 처리_선점과_승인은_활성_guard를_해제하고_승인_guard와_보상_대기를_설정한다() {
        SubscriptionVerification verification = newVerification();
        Instant startedAt = CREATED_AT.plusSeconds(1);
        Instant leaseUntil = startedAt.plusSeconds(60);
        Instant processedAt = startedAt.plusSeconds(2);

        verification.startProcessing(TOKEN, startedAt, leaseUntil);
        verification.approve(TOKEN, processedAt);

        assertThat(verification.getStatus()).isEqualTo(SubscriptionVerificationStatus.APPROVED);
        assertThat(verification.getRewardStatus()).isEqualTo(VerificationRewardStatus.PENDING);
        assertThat(verification.getActiveGuard()).isNull();
        assertThat(verification.getApprovedGuard()).isEqualTo((byte) 1);
        assertThat(verification.getProcessedAt()).isEqualTo(processedAt);
        assertThat(verification.getProcessingLeaseUntil()).isNull();
        assertThat(verification.getRewardRequestId()).isEqualTo(REWARD_REQUEST_ID);
        assertThat(verification.getRewardPeriodKey()).isEqualTo("2026-09-28");
    }

    @Test
    void 거절은_활성_guard를_해제하고_승인_guard를_설정하지_않는다() {
        SubscriptionVerification verification = processingVerification();
        Instant processedAt = CREATED_AT.plusSeconds(3);

        verification.reject(TOKEN, "CHANNEL_MISMATCH", processedAt);

        assertThat(verification.getStatus()).isEqualTo(SubscriptionVerificationStatus.REJECTED);
        assertThat(verification.getReasonCode()).isEqualTo("CHANNEL_MISMATCH");
        assertThat(verification.getActiveGuard()).isNull();
        assertThat(verification.getApprovedGuard()).isNull();
        assertThat(verification.getRewardStatus()).isEqualTo(VerificationRewardStatus.NOT_REQUESTED);
    }

    @Test
    void 증거_부족과_기술_실패도_활성_guard를_해제해_재제출을_허용한다() {
        SubscriptionVerification retryRequired = processingVerification();
        retryRequired.requireRetry(TOKEN, "INSUFFICIENT_EVIDENCE", CREATED_AT.plusSeconds(3));

        SubscriptionVerification failed = pendingAt(CREATED_AT.plusSeconds(10));
        failed.startProcessing(TOKEN, CREATED_AT.plusSeconds(11), CREATED_AT.plusSeconds(71));
        failed.fail(TOKEN, "PROVIDER_TIMEOUT", CREATED_AT.plusSeconds(12));

        assertThat(retryRequired.getStatus()).isEqualTo(SubscriptionVerificationStatus.RETRY_REQUIRED);
        assertThat(retryRequired.getActiveGuard()).isNull();
        assertThat(retryRequired.getApprovedGuard()).isNull();
        assertThat(failed.getStatus()).isEqualTo(SubscriptionVerificationStatus.FAILED);
        assertThat(failed.getActiveGuard()).isNull();
        assertThat(failed.getApprovedGuard()).isNull();
    }

    @Test
    void PROCESSING을_거치지_않고_종료_상태로_전이할_수_없다() {
        SubscriptionVerification verification = newVerification();

        assertThatThrownBy(() -> verification.approve(TOKEN, CREATED_AT.plusSeconds(1)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(verification.getStatus()).isEqualTo(SubscriptionVerificationStatus.PENDING);
        assertThat(verification.getActiveGuard()).isEqualTo((byte) 1);
    }

    @Test
    void 처리_token이_다르면_늦게_도착한_worker가_결과를_저장할_수_없다() {
        SubscriptionVerification verification = processingVerification();

        assertThatThrownBy(() -> verification.approve(
                "40000000-0000-4000-8000-000000000001",
                CREATED_AT.plusSeconds(3)
        )).isInstanceOf(IllegalStateException.class);

        assertThat(verification.getStatus()).isEqualTo(SubscriptionVerificationStatus.PROCESSING);
        assertThat(verification.getActiveGuard()).isEqualTo((byte) 1);
    }

    @Test
    void lease가_만료되면_재선점_전이어도_현재_token으로_결과를_저장할_수_없다() {
        SubscriptionVerification verification = processingVerification();

        assertThatThrownBy(() -> verification.approve(TOKEN, CREATED_AT.plusSeconds(61)))
                .isInstanceOf(IllegalStateException.class);

        assertThat(verification.getStatus()).isEqualTo(SubscriptionVerificationStatus.PROCESSING);
        assertThat(verification.getActiveGuard()).isEqualTo((byte) 1);
    }

    @Test
    void lease가_만료된_processing만_새_token으로_재선점할_수_있다() {
        SubscriptionVerification verification = processingVerification();
        Instant beforeExpiry = CREATED_AT.plusSeconds(30);

        assertThatThrownBy(() -> verification.reclaimProcessing(
                "40000000-0000-4000-8000-000000000001",
                beforeExpiry,
                beforeExpiry.plusSeconds(60)
        )).isInstanceOf(IllegalStateException.class);

        Instant afterExpiry = CREATED_AT.plusSeconds(62);
        String newToken = "40000000-0000-4000-8000-000000000001";
        verification.reclaimProcessing(newToken, afterExpiry, afterExpiry.plusSeconds(60));

        assertThat(verification.getProcessingToken()).isEqualTo(newToken);
        assertThat(verification.getAttemptCount()).isEqualTo(2);
    }

    @Test
    void 승인된_인증의_보상은_재시도_후_승인으로_수렴할_수_있다() {
        SubscriptionVerification verification = processingVerification();
        Instant approvedAt = CREATED_AT.plusSeconds(3);
        verification.approve(TOKEN, approvedAt);

        verification.requireRewardRetry(approvedAt.plusSeconds(60), approvedAt.plusSeconds(1));
        assertThat(verification.getRewardStatus()).isEqualTo(VerificationRewardStatus.RETRY_REQUIRED);

        verification.acceptReward(approvedAt.plusSeconds(61));

        assertThat(verification.getRewardStatus()).isEqualTo(VerificationRewardStatus.ACCEPTED);
        assertThat(verification.getNextAttemptAt()).isNull();
        assertThat(verification.getRewardRequestId()).isEqualTo(REWARD_REQUEST_ID);
        assertThat(verification.getRewardPeriodKey()).isEqualTo("2026-09-28");
    }

    @Test
    void UUID와_SHA256은_정본_형식을_벗어나면_거부한다() {
        assertThatThrownBy(() -> SubscriptionVerification.pending(
                1L, 2L, 3L, "not-a-uuid", "a".repeat(64), "채널", "@channel",
                "subscription-verifications/2026/09/id/image.jpg", "b".repeat(64), "JPEG_V1",
                REWARD_REQUEST_ID, CREATED_AT
        )).isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> SubscriptionVerification.pending(
                1L, 2L, 3L, REQUEST_ID, "A".repeat(64), "채널", "@channel",
                "subscription-verifications/2026/09/id/image.jpg", "b".repeat(64), "JPEG_V1",
                REWARD_REQUEST_ID, CREATED_AT
        )).isInstanceOf(IllegalArgumentException.class);
    }

    private SubscriptionVerification processingVerification() {
        SubscriptionVerification verification = newVerification();
        verification.startProcessing(TOKEN, CREATED_AT.plusSeconds(1), CREATED_AT.plusSeconds(61));
        return verification;
    }

    private SubscriptionVerification newVerification() {
        return pendingAt(CREATED_AT);
    }

    private SubscriptionVerification pendingAt(Instant createdAt) {
        return SubscriptionVerification.pending(
                1L,
                2L,
                3L,
                REQUEST_ID,
                "a".repeat(64),
                "예상치 못한 필름",
                "@unexpectedfilm",
                "subscription-verifications/2026/09/id/image.jpg",
                "b".repeat(64),
                "JPEG_V1",
                REWARD_REQUEST_ID,
                createdAt
        );
    }
}
