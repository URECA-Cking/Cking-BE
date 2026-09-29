package kr.co.cking.subscriptionverification.application.vision;

import java.util.Objects;

/** VLM이 관측한 사실만 담으며 Verification 상태나 보상 여부를 결정하지 않는다. */
public record VisionAnalysisResult(
        VisionPlatform platform,
        String observedChannelName,
        String observedChannelHandle,
        VisionSubscriptionState subscriptionState,
        boolean evidenceSufficient,
        double confidence) {

    public VisionAnalysisResult {
        Objects.requireNonNull(platform, "platform");
        Objects.requireNonNull(subscriptionState, "subscriptionState");
        if (!Double.isFinite(confidence) || confidence < 0.0 || confidence > 1.0) {
            throw new IllegalArgumentException("confidence는 0 이상 1 이하의 유한한 값이어야 합니다.");
        }
        observedChannelName = trimToNull(observedChannelName);
        observedChannelHandle = trimToNull(observedChannelHandle);
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
