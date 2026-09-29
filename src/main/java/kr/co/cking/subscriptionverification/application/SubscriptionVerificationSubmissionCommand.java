package kr.co.cking.subscriptionverification.application;

import java.util.Objects;
import java.util.UUID;

/** HTTP multipart 타입에 의존하지 않는 구독 인증 제출 명령이다. */
public record SubscriptionVerificationSubmissionCommand(
        Long memberId,
        Long creatorId,
        Long missionId,
        UUID requestId,
        byte[] imageBytes
) {
    public SubscriptionVerificationSubmissionCommand {
        Objects.requireNonNull(memberId, "memberId");
        Objects.requireNonNull(creatorId, "creatorId");
        Objects.requireNonNull(missionId, "missionId");
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(imageBytes, "imageBytes");
        imageBytes = imageBytes.clone();
    }

    @Override
    public byte[] imageBytes() {
        return imageBytes.clone();
    }
}
