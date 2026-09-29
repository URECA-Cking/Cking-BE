package kr.co.cking.subscriptionverification.application;

import java.time.Instant;
import java.util.List;
import kr.co.cking.subscriptionverification.domain.SubscriptionVerification;
import kr.co.cking.subscriptionverification.domain.SubscriptionVerificationStatus;
import kr.co.cking.subscriptionverification.domain.VerificationRewardStatus;
import kr.co.cking.subscriptionverification.repository.SubscriptionVerificationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Recovery Scheduler의 조회와 조건부 상태 변경을 짧은 Transaction으로 제공한다. */
@Service
@RequiredArgsConstructor
public class SubscriptionVerificationRecoveryService {

    private static final String EXHAUSTED_REASON = "PROCESSING_ATTEMPTS_EXHAUSTED";
    private static final List<VerificationRewardStatus> RECOVERABLE_REWARD_STATUSES = List.of(
            VerificationRewardStatus.PENDING,
            VerificationRewardStatus.RETRY_REQUIRED
    );

    private final SubscriptionVerificationRepository verificationRepository;

    @Transactional(readOnly = true)
    public List<Long> findProcessingCandidates(
            Instant now,
            Instant pendingBefore,
            int maxAttempts,
            int batchSize) {
        return verificationRepository.findProcessingRecoveryCandidates(
                        SubscriptionVerificationStatus.PENDING,
                        SubscriptionVerificationStatus.PROCESSING,
                        pendingBefore,
                        now,
                        maxAttempts,
                        PageRequest.of(0, batchSize))
                .stream()
                .map(SubscriptionVerification::getVerificationId)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<Long> findExhaustedProcessingIds(
            Instant now,
            int maxAttempts,
            int batchSize) {
        return verificationRepository.findExhaustedProcessingIds(
                SubscriptionVerificationStatus.PROCESSING,
                now,
                maxAttempts,
                PageRequest.of(0, batchSize));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean failExhaustedProcessing(Long verificationId, Instant failedAt, int maxAttempts) {
        return verificationRepository.failExhaustedProcessing(
                verificationId,
                SubscriptionVerificationStatus.PROCESSING,
                SubscriptionVerificationStatus.FAILED,
                EXHAUSTED_REASON,
                failedAt,
                maxAttempts) == 1;
    }

    @Transactional(readOnly = true)
    public List<SubscriptionVerificationRecoveryTarget> findRewardCandidates(
            Instant now,
            int batchSize) {
        return verificationRepository.findRewardRecoveryCandidates(
                        SubscriptionVerificationStatus.APPROVED,
                        RECOVERABLE_REWARD_STATUSES,
                        now,
                        PageRequest.of(0, batchSize))
                .stream()
                .map(this::toRewardTarget)
                .toList();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean scheduleRewardRetry(
            Long verificationId,
            int expectedAttemptCount,
            Instant failedAt,
            Instant nextAttemptAt) {
        return verificationRepository.scheduleRewardRetry(
                verificationId,
                SubscriptionVerificationStatus.APPROVED,
                RECOVERABLE_REWARD_STATUSES,
                VerificationRewardStatus.RETRY_REQUIRED,
                expectedAttemptCount,
                nextAttemptAt,
                failedAt) == 1;
    }

    private SubscriptionVerificationRecoveryTarget toRewardTarget(
            SubscriptionVerification verification) {
        SubscriptionVerificationRewardCommand command = new SubscriptionVerificationRewardCommand(
                verification.getVerificationId(),
                verification.getMemberId(),
                verification.getCreatorId(),
                verification.getMissionId(),
                verification.getRewardRequestId(),
                verification.getRewardPeriodKey());
        return SubscriptionVerificationRecoveryTarget.reward(
                command, verification.getRewardAttemptCount());
    }
}
