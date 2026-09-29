package kr.co.cking.subscriptionverification.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import kr.co.cking.subscriptionverification.domain.SubscriptionVerification;
import kr.co.cking.subscriptionverification.repository.SubscriptionVerificationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Creator 잠금 이후 제출 조건을 재검증하고 PENDING Verification을 원자적으로 저장한다. */
@Service
@RequiredArgsConstructor
class SubscriptionVerificationPersistenceService {

    private final SubscriptionVerificationSubmissionValidator validator;
    private final SubscriptionVerificationRepository verificationRepository;
    private final SubscriptionVerificationImageReuseDetectionService imageReuseDetectionService;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    /** Creator 잠금 뒤 PENDING Verification과 이미지 재사용 감사 기록을 한 Transaction으로 저장한다. */
    @Transactional
    public SubscriptionVerificationSubmissionResult create(
            SubscriptionVerificationPersistenceCommand command
    ) {
        Instant validationAt = clock.instant();
        SubscriptionVerificationSubmissionSource source = validator.validateSourceForUpdate(
                command.memberId(), command.creatorId(), command.missionId(), validationAt);
        Optional<SubscriptionVerification> existing = validator.validateRequest(
                command.memberId(),
                command.creatorId(),
                command.missionId(),
                command.requestId(),
                command.requestFingerprint(),
                validationAt);
        if (existing.isPresent()) {
            return new SubscriptionVerificationSubmissionResult(existing.get(), false);
        }

        imageReuseDetectionService.lockImageHash(command.imageSha256(), clock.instant());
        Instant persistedAt = clock.instant();
        SubscriptionVerification verification = SubscriptionVerification.pending(
                command.memberId(),
                command.creatorId(),
                command.missionId(),
                command.requestId(),
                command.requestFingerprint(),
                source.channel().getChannelName(),
                source.channel().getChannelHandle(),
                command.imageObjectKey(),
                command.imageSha256(),
                command.normalizationVersion(),
                command.rewardRequestId(),
                persistedAt);
        verificationRepository.saveAndFlush(verification);
        imageReuseDetectionService.detectAndRecord(verification, persistedAt);
        eventPublisher.publishEvent(
                new SubscriptionVerificationSubmittedEvent(verification.getVerificationId()));
        return new SubscriptionVerificationSubmissionResult(verification, true);
    }
}
