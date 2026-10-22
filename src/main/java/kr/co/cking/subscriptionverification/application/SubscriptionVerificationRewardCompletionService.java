package kr.co.cking.subscriptionverification.application;

import static kr.co.cking.common.validation.DomainValidator.requirePositive;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import kr.co.cking.subscriptionverification.domain.SubscriptionVerificationStatus;
import kr.co.cking.subscriptionverification.domain.VerificationRewardStatus;
import kr.co.cking.subscriptionverification.repository.SubscriptionVerificationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Ticket ONCE 수락 뒤 Verification의 보상 상태만 짧은 독립 Transaction으로 확정한다. */
@Service
@RequiredArgsConstructor
public class SubscriptionVerificationRewardCompletionService {

    private static final List<VerificationRewardStatus> ACCEPTABLE_STATUSES = List.of(
            VerificationRewardStatus.PENDING,
            VerificationRewardStatus.RETRY_REQUIRED
    );

    private final SubscriptionVerificationRepository verificationRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean accept(Long verificationId, Instant acceptedAt) {
        requirePositive(verificationId, "verificationId");
        Objects.requireNonNull(acceptedAt, "acceptedAt은 필수입니다.");
        return verificationRepository.acceptReward(
                verificationId,
                SubscriptionVerificationStatus.APPROVED,
                ACCEPTABLE_STATUSES,
                VerificationRewardStatus.ACCEPTED,
                acceptedAt
        ) == 1;
    }
}
