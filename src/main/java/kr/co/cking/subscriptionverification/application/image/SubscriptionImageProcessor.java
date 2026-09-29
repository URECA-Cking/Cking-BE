package kr.co.cking.subscriptionverification.application.image;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.image.ImageNormalizer;
import kr.co.cking.common.image.ImagePolicy;
import kr.co.cking.common.image.InvalidImageException;
import kr.co.cking.common.image.NormalizedImage;
import kr.co.cking.subscriptionverification.domain.SubscriptionVerificationErrorCode;
import org.springframework.stereotype.Component;

/**
 * 구독 인증 이미지를 공통 {@link ImageNormalizer}로 검증·정규화한다.
 *
 * <p>VLM이 화면을 읽을 수 있도록 최소 480×480을 요구하고, 잘못된 이미지는 {@code INVALID_VERIFICATION_IMAGE}로
 * 변환한다.
 */
@Component
public class SubscriptionImageProcessor {

    public static final String NORMALIZATION_VERSION = ImageNormalizer.NORMALIZATION_VERSION;
    public static final int MAX_SOURCE_BYTES = ImageNormalizer.MAX_SOURCE_BYTES;
    public static final int MIN_WIDTH = 480;
    public static final int MIN_HEIGHT = 480;
    public static final long MAX_PIXEL_COUNT = ImageNormalizer.MAX_PIXEL_COUNT;
    public static final int MAX_LONG_EDGE = 2048;

    private static final ImagePolicy POLICY = new ImagePolicy(MIN_WIDTH, MIN_HEIGHT, MAX_LONG_EDGE);

    private final ImageNormalizer normalizer = new ImageNormalizer();

    public ProcessedSubscriptionImage process(byte[] sourceBytes) {
        NormalizedImage normalized;
        try {
            normalized = normalizer.normalize(sourceBytes, POLICY);
        } catch (InvalidImageException e) {
            throw new BusinessException(
                    SubscriptionVerificationErrorCode.INVALID_VERIFICATION_IMAGE);
        }

        return new ProcessedSubscriptionImage(
                normalized.normalizedImageBytes(),
                normalized.sourceImageSha256(),
                normalized.normalizedImageSha256(),
                normalized.normalizationVersion(),
                normalized.width(),
                normalized.height());
    }
}
