package kr.co.cking.subscriptionverification.application.vision;

import java.util.Objects;

import kr.co.cking.subscriptionverification.domain.CreatorYoutubeChannel;

/** Provider의 관측 결과를 결정적인 구독 인증 판정으로 변환하는 순수 정책이다. */
public final class SubscriptionVerificationDecisionPolicy {

    private final double confidenceThreshold;

    public SubscriptionVerificationDecisionPolicy(double confidenceThreshold) {
        if (!Double.isFinite(confidenceThreshold)
                || confidenceThreshold < 0.0
                || confidenceThreshold > 1.0) {
            throw new IllegalArgumentException("confidenceThreshold는 0 이상 1 이하이어야 합니다.");
        }
        this.confidenceThreshold = confidenceThreshold;
    }

    public SubscriptionVerificationDecision decide(
            String targetChannelHandle,
            VisionAnalysisResult analysis) {
        Objects.requireNonNull(analysis, "analysis");
        String normalizedTargetHandle = CreatorYoutubeChannel.normalizeHandle(targetChannelHandle);

        if (!analysis.evidenceSufficient()) {
            return SubscriptionVerificationDecision.retryRequired(
                    SubscriptionVerificationDecisionReason.INSUFFICIENT_EVIDENCE);
        }
        if (analysis.confidence() < confidenceThreshold) {
            return SubscriptionVerificationDecision.retryRequired(
                    SubscriptionVerificationDecisionReason.LOW_CONFIDENCE);
        }
        if (analysis.platform() == VisionPlatform.UNKNOWN) {
            return SubscriptionVerificationDecision.retryRequired(
                    SubscriptionVerificationDecisionReason.INSUFFICIENT_EVIDENCE);
        }
        if (analysis.platform() != VisionPlatform.YOUTUBE) {
            return SubscriptionVerificationDecision.rejected(
                    SubscriptionVerificationDecisionReason.PLATFORM_MISMATCH);
        }
        if (analysis.subscriptionState() == VisionSubscriptionState.UNKNOWN) {
            return SubscriptionVerificationDecision.retryRequired(
                    SubscriptionVerificationDecisionReason.INSUFFICIENT_EVIDENCE);
        }

        String observedHandle = normalizeObservedHandle(analysis.observedChannelHandle());
        if (observedHandle == null) {
            return SubscriptionVerificationDecision.retryRequired(
                    SubscriptionVerificationDecisionReason.INSUFFICIENT_EVIDENCE);
        }
        if (!normalizedTargetHandle.equals(observedHandle)) {
            return SubscriptionVerificationDecision.rejected(
                    SubscriptionVerificationDecisionReason.CHANNEL_MISMATCH);
        }
        if (analysis.subscriptionState() == VisionSubscriptionState.NOT_SUBSCRIBED) {
            return SubscriptionVerificationDecision.rejected(
                    SubscriptionVerificationDecisionReason.NOT_SUBSCRIBED);
        }
        return SubscriptionVerificationDecision.approved();
    }

    private String normalizeObservedHandle(String observedHandle) {
        if (observedHandle == null) {
            return null;
        }
        try {
            return CreatorYoutubeChannel.normalizeHandle(observedHandle);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }
}
