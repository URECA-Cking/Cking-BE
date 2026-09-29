package kr.co.cking.subscriptionverification.application;

import java.nio.charset.StandardCharsets;
import kr.co.cking.common.image.ImageSha256;

/** 구독 인증 제출의 멱등성 비교에 사용하는 canonical fingerprint를 만든다. */
public final class SubscriptionVerificationFingerprint {

    private SubscriptionVerificationFingerprint() {}

    public static String calculate(
            Long memberId,
            Long creatorId,
            Long missionId,
            String imageSha256
    ) {
        String canonical = "%d:%d:%d:%s".formatted(memberId, creatorId, missionId, imageSha256);
        return ImageSha256.calculate(canonical.getBytes(StandardCharsets.UTF_8));
    }
}
