package kr.co.cking.subscriptionverification.application;

import static kr.co.cking.common.validation.DomainValidator.requirePositive;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import kr.co.cking.subscriptionverification.domain.SubscriptionVerificationStatus;
import kr.co.cking.subscriptionverification.domain.VerificationRewardStatus;
import kr.co.cking.subscriptionverification.repository.SubscriptionVerificationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 현재 fencing token의 소유자만 Processing 결과를 저장한다. */
@Service
@RequiredArgsConstructor
public class SubscriptionVerificationProcessingCompletionService {

    private final SubscriptionVerificationRepository verificationRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean complete(
            Long verificationId,
            String processingToken,
            SubscriptionVerificationProcessingOutcome outcome,
            String reasonCode,
            Instant processedAt
    ) {
        requirePositive(verificationId, "verificationId");
        requireUuid(processingToken);
        Objects.requireNonNull(outcome, "outcome은 필수입니다.");
        Objects.requireNonNull(processedAt, "processedAt은 필수입니다.");

        boolean approved = outcome == SubscriptionVerificationProcessingOutcome.APPROVED;
        String normalizedReason = approved ? null : requireReasonCode(reasonCode);
        VerificationRewardStatus rewardStatus = approved
                ? VerificationRewardStatus.PENDING
                : VerificationRewardStatus.NOT_REQUESTED;
        Byte approvedGuard = approved ? (byte) 1 : null;

        return verificationRepository.completeProcessing(
                verificationId,
                processingToken,
                SubscriptionVerificationStatus.PROCESSING,
                outcome.status(),
                normalizedReason,
                rewardStatus,
                approvedGuard,
                processedAt
        ) == 1;
    }

    private static void requireUuid(String processingToken) {
        try {
            UUID.fromString(processingToken);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("processingToken은 UUID 형식이어야 합니다.", exception);
        }
    }

    private static String requireReasonCode(String reasonCode) {
        if (reasonCode == null || reasonCode.isBlank() || reasonCode.length() > 50) {
            throw new IllegalArgumentException("reasonCode는 필수이며 50자 이하여야 합니다.");
        }
        return reasonCode;
    }
}
