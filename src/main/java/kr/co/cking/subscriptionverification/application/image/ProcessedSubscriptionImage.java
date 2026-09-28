package kr.co.cking.subscriptionverification.application.image;

import java.util.Objects;

/** VLM Provider나 저장소 형식에 의존하지 않는 구독 인증 이미지 처리 결과. */
public record ProcessedSubscriptionImage(
        byte[] normalizedImageBytes,
        String sourceImageSha256,
        String normalizedImageSha256,
        String normalizationVersion,
        int width,
        int height) {

    public ProcessedSubscriptionImage {
        Objects.requireNonNull(normalizedImageBytes, "normalizedImageBytes");
        Objects.requireNonNull(sourceImageSha256, "sourceImageSha256");
        Objects.requireNonNull(normalizedImageSha256, "normalizedImageSha256");
        Objects.requireNonNull(normalizationVersion, "normalizationVersion");
        normalizedImageBytes = normalizedImageBytes.clone();
    }

    @Override
    public byte[] normalizedImageBytes() {
        return normalizedImageBytes.clone();
    }
}
