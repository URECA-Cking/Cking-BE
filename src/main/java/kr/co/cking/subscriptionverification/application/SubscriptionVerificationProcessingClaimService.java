package kr.co.cking.subscriptionverification.application;

import static kr.co.cking.common.validation.DomainValidator.requirePositive;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import kr.co.cking.subscriptionverification.domain.SubscriptionVerification;
import kr.co.cking.subscriptionverification.domain.SubscriptionVerificationStatus;
import kr.co.cking.subscriptionverification.repository.SubscriptionVerificationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 외부 VLM 호출 전에 짧은 독립 Transaction으로 처리 소유권을 선점한다. */
@Service
@RequiredArgsConstructor
public class SubscriptionVerificationProcessingClaimService {

    private final SubscriptionVerificationRepository verificationRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<SubscriptionVerificationProcessingClaim> claim(
            Long verificationId,
            Instant startedAt,
            Duration leaseDuration
    ) {
        requirePositive(verificationId, "verificationId");
        if (startedAt == null) {
            throw new IllegalArgumentException("startedAt은 필수입니다.");
        }
        if (leaseDuration == null || leaseDuration.isZero() || leaseDuration.isNegative()) {
            throw new IllegalArgumentException("leaseDuration은 양수여야 합니다.");
        }

        Instant leaseUntil;
        try {
            leaseUntil = startedAt.plus(leaseDuration);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("처리 lease 종료 시각을 계산할 수 없습니다.", exception);
        }
        String processingToken = UUID.randomUUID().toString();
        int affected = verificationRepository.claimProcessing(
                verificationId, processingToken, startedAt, leaseUntil);
        if (affected == 0) {
            return Optional.empty();
        }

        SubscriptionVerification claimed = verificationRepository
                .findByVerificationIdAndStatusAndProcessingToken(
                        verificationId, SubscriptionVerificationStatus.PROCESSING, processingToken)
                .orElseThrow(() -> new IllegalStateException("선점한 구독 인증을 다시 조회할 수 없습니다."));
        return Optional.of(new SubscriptionVerificationProcessingClaim(
                claimed.getVerificationId(),
                claimed.getProcessingToken(),
                claimed.getMemberId(),
                claimed.getCreatorId(),
                claimed.getMissionId(),
                claimed.getImageObjectKey(),
                claimed.getTargetChannelName(),
                claimed.getTargetChannelHandle(),
                claimed.getRewardRequestId(),
                claimed.getRewardPeriodKey(),
                claimed.getAttemptCount(),
                claimed.getProcessingLeaseUntil()
        ));
    }
}
