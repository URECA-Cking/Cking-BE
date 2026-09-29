package kr.co.cking.subscriptionverification.application;

import java.time.Instant;
import kr.co.cking.subscriptionverification.domain.SubscriptionVerification;
import kr.co.cking.subscriptionverification.domain.SubscriptionVerificationImageReuse;
import kr.co.cking.subscriptionverification.domain.SubscriptionVerificationImageReuseType;
import kr.co.cking.subscriptionverification.repository.SubscriptionVerificationImageHashLockRepository;
import kr.co.cking.subscriptionverification.repository.SubscriptionVerificationImageReuseRepository;
import kr.co.cking.subscriptionverification.repository.SubscriptionVerificationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** 정규화 이미지 hash 기준으로 최초·재사용을 기록하되 인증 판정에는 개입하지 않는다. */
@Service
@RequiredArgsConstructor
class SubscriptionVerificationImageReuseDetectionService {

    private final SubscriptionVerificationImageHashLockRepository hashLockRepository;
    private final SubscriptionVerificationRepository verificationRepository;
    private final SubscriptionVerificationImageReuseRepository imageReuseRepository;

    /** 같은 hash 제출을 직렬화한 뒤 이전 Verification과 재사용 유형을 감사 기록으로 저장한다. */
    void detectAndRecord(SubscriptionVerification verification, Instant detectedAt) {
        hashLockRepository.insertIgnore(verification.getImageSha256(), detectedAt);
        hashLockRepository.findByImageSha256ForUpdate(verification.getImageSha256())
                .orElseThrow(() -> new IllegalStateException("이미지 hash 잠금 행을 찾을 수 없습니다."));

        SubscriptionVerification previous = verificationRepository
                .findFirstPreviousByImageSha256(
                        verification.getImageSha256(), verification.getCreatedAt(), verification.getVerificationId())
                .orElse(null);
        imageReuseRepository.save(new SubscriptionVerificationImageReuse(
                verification.getVerificationId(),
                previous == null ? null : previous.getVerificationId(),
                resolveReuseType(verification, previous),
                detectedAt));
    }

    /** 이전 제출자와 Creator 관계로 재사용 유형을 결정한다. */
    private SubscriptionVerificationImageReuseType resolveReuseType(
            SubscriptionVerification current,
            SubscriptionVerification previous
    ) {
        if (previous == null) {
            return SubscriptionVerificationImageReuseType.FIRST_USE;
        }
        if (!current.getMemberId().equals(previous.getMemberId())) {
            return SubscriptionVerificationImageReuseType.DIFFERENT_MEMBER;
        }
        return current.getCreatorId().equals(previous.getCreatorId())
                ? SubscriptionVerificationImageReuseType.SAME_MEMBER_SAME_CREATOR
                : SubscriptionVerificationImageReuseType.SAME_MEMBER_DIFFERENT_CREATOR;
    }
}
