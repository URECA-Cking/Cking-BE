package kr.co.cking.post.application.image;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.image.ImageNormalizer;
import kr.co.cking.common.image.ImagePolicy;
import kr.co.cking.common.image.InvalidImageException;
import kr.co.cking.common.image.NormalizedImage;
import kr.co.cking.post.domain.PostErrorCode;
import org.springframework.stereotype.Component;

/** 게시글 이미지를 공통 {@link ImageNormalizer}로 검증·정규화한다. 잘못된 이미지는 {@code INVALID_POST_IMAGE}다. */
@Component
public class PostImageProcessor {

    public static final int MIN_WIDTH = 200;
    public static final int MIN_HEIGHT = 200;
    public static final int MAX_LONG_EDGE = 2048;

    private static final ImagePolicy POLICY = new ImagePolicy(MIN_WIDTH, MIN_HEIGHT, MAX_LONG_EDGE);

    private final ImageNormalizer normalizer = new ImageNormalizer();

    public NormalizedImage process(byte[] sourceBytes) {
        try {
            return normalizer.normalize(sourceBytes, POLICY);
        } catch (InvalidImageException e) {
            throw new BusinessException(PostErrorCode.INVALID_POST_IMAGE);
        }
    }
}
