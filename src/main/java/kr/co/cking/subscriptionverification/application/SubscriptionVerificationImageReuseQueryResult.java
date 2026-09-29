package kr.co.cking.subscriptionverification.application;

import java.time.Instant;
import kr.co.cking.subscriptionverification.domain.SubscriptionVerificationImageReuse;
import kr.co.cking.subscriptionverification.domain.SubscriptionVerificationImageReuseType;

/** 운영자가 조회하는 이미지 재사용 탐지 결과다. */
public record SubscriptionVerificationImageReuseQueryResult(
        Long verificationId,
        Long matchedVerificationId,
        SubscriptionVerificationImageReuseType reuseType,
        Instant detectedAt
) {

    /** 영속된 탐지 결과를 이미지 정보 없이 운영 조회 응답으로 변환한다. */
    public static SubscriptionVerificationImageReuseQueryResult from(SubscriptionVerificationImageReuse reuse) {
        return new SubscriptionVerificationImageReuseQueryResult(
                reuse.getVerificationId(),
                reuse.getMatchedVerificationId(),
                reuse.getReuseType(),
                reuse.getDetectedAt());
    }
}
