package kr.co.cking.common.image;

import java.util.Objects;

/** 저장소나 도메인 형식에 의존하지 않는 이미지 정규화 결과. */
public record NormalizedImage(
        byte[] normalizedImageBytes,
        String sourceImageSha256,
        String normalizedImageSha256,
        String normalizationVersion,
        int width,
        int height) {

    public NormalizedImage {
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
