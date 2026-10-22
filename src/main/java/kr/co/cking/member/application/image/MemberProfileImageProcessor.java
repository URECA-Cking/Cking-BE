package kr.co.cking.member.application.image;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.image.ImageNormalizer;
import kr.co.cking.common.image.ImagePolicy;
import kr.co.cking.common.image.InvalidImageException;
import kr.co.cking.common.image.NormalizedImage;
import kr.co.cking.member.domain.MemberErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 개인 프로필 사진에 공통 정규화 규칙과 최소 표시 크기를 적용한다. */
@Component
@RequiredArgsConstructor
public class MemberProfileImageProcessor {

    public static final int MIN_WIDTH = 200;
    public static final int MIN_HEIGHT = 200;
    public static final int MAX_LONG_EDGE = 2048;

    private static final ImagePolicy POLICY = new ImagePolicy(MIN_WIDTH, MIN_HEIGHT, MAX_LONG_EDGE);

    private final ImageNormalizer imageNormalizer;

    public NormalizedImage process(byte[] imageBytes) {
        try {
            return imageNormalizer.normalize(imageBytes, POLICY);
        } catch (InvalidImageException exception) {
            throw new BusinessException(MemberErrorCode.INVALID_PROFILE_IMAGE);
        }
    }
}
