package kr.co.cking.subscriptionverification.application.vision;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class SubscriptionVerificationDecisionPolicyTest {

    private final SubscriptionVerificationDecisionPolicy policy =
            new SubscriptionVerificationDecisionPolicy(0.9);

    @Test
    void 승인_조건을_모두_충족하면_승인한다() {
        SubscriptionVerificationDecision decision = policy.decide(
                "@UnexpectedFilm",
                result(
                        VisionPlatform.YOUTUBE,
                        " @@unexpectedfilm ",
                        VisionSubscriptionState.SUBSCRIBED,
                        true,
                        0.9));

        assertThat(decision.status()).isEqualTo(SubscriptionVerificationDecisionStatus.APPROVED);
        assertThat(decision.reason()).isNull();
        assertThat(decision.reasonCode()).isNull();
    }

    @Test
    void 채널명은_달라도_handle이_일치하면_승인할_수_있다() {
        VisionAnalysisResult analysis = new VisionAnalysisResult(
                VisionPlatform.YOUTUBE,
                "변경된 표시명",
                "@unexpectedfilm",
                VisionSubscriptionState.SUBSCRIBED,
                true,
                0.99);

        SubscriptionVerificationDecision decision =
                policy.decide("@unexpectedfilm", analysis);

        assertThat(decision.status()).isEqualTo(SubscriptionVerificationDecisionStatus.APPROVED);
    }

    @Test
    void 다른_플랫폼이_명확하면_거절한다() {
        assertDecision(
                result(
                        VisionPlatform.OTHER,
                        "@unexpectedfilm",
                        VisionSubscriptionState.SUBSCRIBED,
                        true,
                        0.99),
                SubscriptionVerificationDecisionStatus.REJECTED,
                SubscriptionVerificationDecisionReason.PLATFORM_MISMATCH);
    }

    @Test
    void 다른_채널이_명확하면_거절한다() {
        assertDecision(
                result(
                        VisionPlatform.YOUTUBE,
                        "@other",
                        VisionSubscriptionState.SUBSCRIBED,
                        true,
                        0.99),
                SubscriptionVerificationDecisionStatus.REJECTED,
                SubscriptionVerificationDecisionReason.CHANNEL_MISMATCH);
    }

    @Test
    void 대상_채널에서_미구독이_명확하면_거절한다() {
        assertDecision(
                result(
                        VisionPlatform.YOUTUBE,
                        "@unexpectedfilm",
                        VisionSubscriptionState.NOT_SUBSCRIBED,
                        true,
                        0.99),
                SubscriptionVerificationDecisionStatus.REJECTED,
                SubscriptionVerificationDecisionReason.NOT_SUBSCRIBED);
    }

    @Test
    void 증거가_부족하면_관측값과_관계없이_재제출을_요청한다() {
        assertDecision(
                result(
                        VisionPlatform.OTHER,
                        "@other",
                        VisionSubscriptionState.NOT_SUBSCRIBED,
                        false,
                        0.99),
                SubscriptionVerificationDecisionStatus.RETRY_REQUIRED,
                SubscriptionVerificationDecisionReason.INSUFFICIENT_EVIDENCE);
    }

    @Test
    void confidence가_threshold보다_낮으면_재제출을_요청한다() {
        assertDecision(
                result(
                        VisionPlatform.YOUTUBE,
                        "@unexpectedfilm",
                        VisionSubscriptionState.SUBSCRIBED,
                        true,
                        0.899),
                SubscriptionVerificationDecisionStatus.RETRY_REQUIRED,
                SubscriptionVerificationDecisionReason.LOW_CONFIDENCE);
    }

    @Test
    void confidence가_threshold와_같으면_승인할_수_있다() {
        SubscriptionVerificationDecision decision = policy.decide(
                "@unexpectedfilm",
                result(
                        VisionPlatform.YOUTUBE,
                        "@unexpectedfilm",
                        VisionSubscriptionState.SUBSCRIBED,
                        true,
                        0.9));

        assertThat(decision.status()).isEqualTo(SubscriptionVerificationDecisionStatus.APPROVED);
    }

    @Test
    void 플랫폼이나_구독상태가_UNKNOWN이면_재제출을_요청한다() {
        assertDecision(
                result(
                        VisionPlatform.UNKNOWN,
                        "@unexpectedfilm",
                        VisionSubscriptionState.SUBSCRIBED,
                        true,
                        0.99),
                SubscriptionVerificationDecisionStatus.RETRY_REQUIRED,
                SubscriptionVerificationDecisionReason.INSUFFICIENT_EVIDENCE);
        assertDecision(
                result(
                        VisionPlatform.YOUTUBE,
                        "@unexpectedfilm",
                        VisionSubscriptionState.UNKNOWN,
                        true,
                        0.99),
                SubscriptionVerificationDecisionStatus.RETRY_REQUIRED,
                SubscriptionVerificationDecisionReason.INSUFFICIENT_EVIDENCE);
    }

    @Test
    void 구독상태가_UNKNOWN이어도_다른_채널이_명확하면_채널_불일치로_거절한다() {
        assertDecision(
                result(
                        VisionPlatform.YOUTUBE,
                        "@clearly-other-channel",
                        VisionSubscriptionState.UNKNOWN,
                        true,
                        0.99),
                SubscriptionVerificationDecisionStatus.REJECTED,
                SubscriptionVerificationDecisionReason.CHANNEL_MISMATCH);
    }

    @Test
    void 관측_handle이_없거나_유효하지_않으면_재제출을_요청한다() {
        assertDecision(
                result(
                        VisionPlatform.YOUTUBE,
                        null,
                        VisionSubscriptionState.SUBSCRIBED,
                        true,
                        0.99),
                SubscriptionVerificationDecisionStatus.RETRY_REQUIRED,
                SubscriptionVerificationDecisionReason.INSUFFICIENT_EVIDENCE);
        assertDecision(
                result(
                        VisionPlatform.YOUTUBE,
                        "bad handle",
                        VisionSubscriptionState.SUBSCRIBED,
                        true,
                        0.99),
                SubscriptionVerificationDecisionStatus.RETRY_REQUIRED,
                SubscriptionVerificationDecisionReason.INSUFFICIENT_EVIDENCE);
    }

    @Test
    void confidence_threshold가_유효하지_않으면_정책을_만들_수_없다() {
        assertThatThrownBy(() -> new SubscriptionVerificationDecisionPolicy(-0.1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SubscriptionVerificationDecisionPolicy(1.1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SubscriptionVerificationDecisionPolicy(Double.NaN))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 승인과_비승인_판정의_reason_불변조건을_검증한다() {
        assertThatThrownBy(() -> new SubscriptionVerificationDecision(
                        SubscriptionVerificationDecisionStatus.APPROVED,
                        SubscriptionVerificationDecisionReason.LOW_CONFIDENCE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SubscriptionVerificationDecision(
                        SubscriptionVerificationDecisionStatus.REJECTED, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private void assertDecision(
            VisionAnalysisResult result,
            SubscriptionVerificationDecisionStatus expectedStatus,
            SubscriptionVerificationDecisionReason expectedReason) {
        SubscriptionVerificationDecision decision = policy.decide("@unexpectedfilm", result);

        assertThat(decision.status()).isEqualTo(expectedStatus);
        assertThat(decision.reason()).isEqualTo(expectedReason);
        assertThat(decision.reasonCode()).isEqualTo(expectedReason.name());
    }

    private VisionAnalysisResult result(
            VisionPlatform platform,
            String observedHandle,
            VisionSubscriptionState state,
            boolean evidenceSufficient,
            double confidence) {
        return new VisionAnalysisResult(
                platform,
                "예상치 못한 필름",
                observedHandle,
                state,
                evidenceSufficient,
                confidence);
    }
}
