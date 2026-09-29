package kr.co.cking.subscriptionverification.application;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import kr.co.cking.common.storage.ObjectStorage;
import kr.co.cking.subscriptionverification.application.image.ProcessedSubscriptionImage;
import kr.co.cking.subscriptionverification.application.image.SubscriptionImageProcessor;
import kr.co.cking.subscriptionverification.domain.SubscriptionVerification;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/** 이미지 처리·Object 저장과 짧은 DB 저장 Transaction을 연결하는 제출 오케스트레이터다. */
@Service
@Slf4j
public class SubscriptionVerificationSubmissionService {

    private final SubscriptionImageProcessor imageProcessor;
    private final ObjectStorage objectStorage;
    private final SubscriptionVerificationAvailability availability;
    private final SubscriptionVerificationSubmissionValidator validator;
    private final SubscriptionVerificationPersistenceService persistenceService;
    private final Clock clock;

    public SubscriptionVerificationSubmissionService(
            SubscriptionImageProcessor imageProcessor,
            ObjectStorage objectStorage,
            SubscriptionVerificationAvailability availability,
            SubscriptionVerificationSubmissionValidator validator,
            SubscriptionVerificationPersistenceService persistenceService,
            Clock clock
    ) {
        this.imageProcessor = imageProcessor;
        this.objectStorage = objectStorage;
        this.availability = availability;
        this.validator = validator;
        this.persistenceService = persistenceService;
        this.clock = clock;
    }

    public SubscriptionVerificationSubmissionResult submit(
            SubscriptionVerificationSubmissionCommand command
    ) {
        availability.requireSubmissionEnabled();

        Instant submittedAt = clock.instant();
        validator.validateSource(
                command.memberId(), command.creatorId(), command.missionId(), submittedAt);

        ProcessedSubscriptionImage image = imageProcessor.process(command.imageBytes());
        String requestId = command.requestId().toString();
        String fingerprint = SubscriptionVerificationFingerprint.calculate(
                command.memberId(),
                command.creatorId(),
                command.missionId(),
                image.normalizedImageSha256());

        Optional<SubscriptionVerification> existing = validator.validateRequest(
                command.memberId(),
                command.creatorId(),
                command.missionId(),
                requestId,
                fingerprint,
                submittedAt);
        if (existing.isPresent()) {
            return new SubscriptionVerificationSubmissionResult(existing.get(), false);
        }

        String objectKey = objectKey(submittedAt);
        objectStorage.put(objectKey, image.normalizedImageBytes(), "image/jpeg");
        SubscriptionVerificationSubmissionResult result;
        try {
            result = persistenceService.create(new SubscriptionVerificationPersistenceCommand(
                    command.memberId(),
                    command.creatorId(),
                    command.missionId(),
                    requestId,
                    fingerprint,
                    objectKey,
                    image.normalizedImageSha256(),
                    image.normalizationVersion(),
                    UUID.randomUUID().toString()));
        } catch (DataIntegrityViolationException exception) {
            deleteQuietly(objectKey);
            Optional<SubscriptionVerification> concurrentExisting = validator.validateRequest(
                    command.memberId(),
                    command.creatorId(),
                    command.missionId(),
                    requestId,
                    fingerprint,
                    submittedAt);
            if (concurrentExisting.isPresent()) {
                return new SubscriptionVerificationSubmissionResult(concurrentExisting.get(), false);
            }
            throw exception;
        } catch (RuntimeException exception) {
            deleteQuietly(objectKey);
            throw exception;
        }

        if (!result.created()) {
            deleteQuietly(objectKey);
        }
        return result;
    }

    private String objectKey(Instant submittedAt) {
        var utc = submittedAt.atZone(ZoneOffset.UTC);
        return "subscription-verifications/%04d/%02d/%s/image.jpg"
                .formatted(utc.getYear(), utc.getMonthValue(), UUID.randomUUID());
    }

    private void deleteQuietly(String objectKey) {
        try {
            objectStorage.delete(objectKey);
        } catch (RuntimeException cleanupFailure) {
            log.warn("구독 인증 Object 보상 삭제에 실패했습니다. objectKey={}", objectKey, cleanupFailure);
        }
    }
}
