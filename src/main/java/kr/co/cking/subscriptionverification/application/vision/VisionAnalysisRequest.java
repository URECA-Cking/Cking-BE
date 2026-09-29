package kr.co.cking.subscriptionverification.application.vision;

import java.util.Objects;

import kr.co.cking.subscriptionverification.domain.CreatorYoutubeChannel;

/** VLM에 전달할 정규화 이미지와 Verification 생성 시 동결한 채널 정보다. */
public record VisionAnalysisRequest(
        byte[] normalizedJpegBytes,
        String targetChannelName,
        String targetChannelHandle) {

    public VisionAnalysisRequest {
        Objects.requireNonNull(normalizedJpegBytes, "normalizedJpegBytes");
        if (normalizedJpegBytes.length == 0) {
            throw new IllegalArgumentException("normalizedJpegBytes는 비어 있을 수 없습니다.");
        }
        normalizedJpegBytes = normalizedJpegBytes.clone();
        targetChannelName = CreatorYoutubeChannel.normalizeName(targetChannelName);
        targetChannelHandle = CreatorYoutubeChannel.normalizeHandle(targetChannelHandle);
    }

    @Override
    public byte[] normalizedJpegBytes() {
        return normalizedJpegBytes.clone();
    }
}
