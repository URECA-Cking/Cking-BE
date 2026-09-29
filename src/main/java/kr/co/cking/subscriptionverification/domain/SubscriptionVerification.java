package kr.co.cking.subscriptionverification.domain;

import static kr.co.cking.common.validation.DomainValidator.requirePositive;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.regex.Pattern;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** YouTube 구독 인증의 입력, 처리 상태와 보상 멱등성 정보를 보존한다. */
@Getter
@Entity
@Table(
        name = "subscription_verification",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_subscription_verification_request", columnNames = "request_id"),
                @UniqueConstraint(name = "uk_subscription_verification_reward_request", columnNames = "reward_request_id"),
                @UniqueConstraint(
                        name = "uk_subscription_verification_active",
                        columnNames = {"member_id", "creator_id", "mission_id", "active_guard"}
                ),
                @UniqueConstraint(
                        name = "uk_subscription_verification_approved",
                        columnNames = {"member_id", "creator_id", "mission_id", "approved_guard"}
                )
        },
        indexes = {
                @Index(
                        name = "idx_subscription_verification_recovery",
                        columnList = "status,next_attempt_at"
                ),
                @Index(
                        name = "idx_subscription_verification_image_hash",
                        columnList = "image_sha256"
                ),
                @Index(
                        name = "idx_subscription_verification_history",
                        columnList = "member_id,creator_id,mission_id,created_at"
                )
        }
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SubscriptionVerification {

    private static final Pattern UUID_PATTERN = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"
    );
    private static final Pattern SHA256_PATTERN = Pattern.compile("^[0-9a-f]{64}$");
    private static final DateTimeFormatter PERIOD_KEY_FORMAT = DateTimeFormatter.ISO_LOCAL_DATE;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "verification_id")
    private Long verificationId;

    @Column(name = "member_id", nullable = false, updatable = false)
    private Long memberId;

    @Column(name = "creator_id", nullable = false, updatable = false)
    private Long creatorId;

    @Column(name = "mission_id", nullable = false, updatable = false)
    private Long missionId;

    @Column(name = "request_id", nullable = false, updatable = false, length = 36)
    private String requestId;

    @Column(name = "request_fingerprint", nullable = false, updatable = false, length = 64)
    private String requestFingerprint;

    @Column(name = "target_channel_name", nullable = false, updatable = false, length = 100)
    private String targetChannelName;

    @Column(name = "target_channel_handle", nullable = false, updatable = false, length = 100)
    private String targetChannelHandle;

    @Column(name = "image_object_key", nullable = false, updatable = false, length = 500)
    private String imageObjectKey;

    @Column(name = "image_sha256", nullable = false, updatable = false, length = 64)
    private String imageSha256;

    @Column(name = "normalization_version", nullable = false, updatable = false, length = 30)
    private String normalizationVersion;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private SubscriptionVerificationStatus status;

    @Column(name = "reason_code", length = 50)
    private String reasonCode;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "processing_token", length = 36)
    private String processingToken;

    @Column(name = "processing_started_at")
    private Instant processingStartedAt;

    @Column(name = "processing_lease_until")
    private Instant processingLeaseUntil;

    @Column(name = "next_attempt_at")
    private Instant nextAttemptAt;

    @Column(name = "processed_at")
    private Instant processedAt;

    @Column(name = "reward_request_id", nullable = false, updatable = false, length = 36)
    private String rewardRequestId;

    @Column(name = "reward_period_key", nullable = false, updatable = false, length = 10)
    private String rewardPeriodKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "reward_status", nullable = false, length = 30)
    private VerificationRewardStatus rewardStatus;

    @Column(name = "active_guard")
    private Byte activeGuard;

    @Column(name = "approved_guard")
    private Byte approvedGuard;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    public static SubscriptionVerification pending(
            Long memberId,
            Long creatorId,
            Long missionId,
            String requestId,
            String requestFingerprint,
            String targetChannelName,
            String targetChannelHandle,
            String imageObjectKey,
            String imageSha256,
            String normalizationVersion,
            String rewardRequestId,
            Instant createdAt
    ) {
        requirePositive(memberId, "memberId");
        requirePositive(creatorId, "creatorId");
        requirePositive(missionId, "missionId");
        requireUuid(requestId, "requestId");
        requireSha256(requestFingerprint, "requestFingerprint");
        requireText(targetChannelName, "targetChannelName", 100);
        requireText(targetChannelHandle, "targetChannelHandle", 100);
        requireText(imageObjectKey, "imageObjectKey", 500);
        requireSha256(imageSha256, "imageSha256");
        requireText(normalizationVersion, "normalizationVersion", 30);
        requireUuid(rewardRequestId, "rewardRequestId");
        Objects.requireNonNull(createdAt, "createdAt은 필수입니다.");

        SubscriptionVerification verification = new SubscriptionVerification();
        verification.memberId = memberId;
        verification.creatorId = creatorId;
        verification.missionId = missionId;
        verification.requestId = requestId;
        verification.requestFingerprint = requestFingerprint;
        verification.targetChannelName = targetChannelName;
        verification.targetChannelHandle = targetChannelHandle;
        verification.imageObjectKey = imageObjectKey;
        verification.imageSha256 = imageSha256;
        verification.normalizationVersion = normalizationVersion;
        verification.status = SubscriptionVerificationStatus.PENDING;
        verification.attemptCount = 0;
        verification.rewardRequestId = rewardRequestId;
        verification.rewardPeriodKey = PERIOD_KEY_FORMAT.format(createdAt.atZone(ZoneOffset.UTC));
        verification.rewardStatus = VerificationRewardStatus.NOT_REQUESTED;
        verification.activeGuard = (byte) 1;
        verification.createdAt = createdAt;
        verification.updatedAt = createdAt;
        return verification;
    }

    /** 대기 중인 인증을 선점하고 처리 lease를 시작한다. */
    public void startProcessing(String token, Instant startedAt, Instant leaseUntil) {
        requireStatus(SubscriptionVerificationStatus.PENDING);
        requireUuid(token, "processingToken");
        requireLease(startedAt, leaseUntil);
        processingToken = token;
        processingStartedAt = startedAt;
        processingLeaseUntil = leaseUntil;
        attemptCount = Math.incrementExact(attemptCount);
        status = SubscriptionVerificationStatus.PROCESSING;
        reasonCode = null;
        nextAttemptAt = null;
        updatedAt = startedAt;
    }

    /** 만료된 처리 lease를 새 token으로 재선점한다. */
    public void reclaimProcessing(String token, Instant startedAt, Instant leaseUntil) {
        requireStatus(SubscriptionVerificationStatus.PROCESSING);
        if (processingLeaseUntil == null || processingLeaseUntil.isAfter(startedAt)) {
            throw new IllegalStateException("처리 lease가 아직 만료되지 않았습니다.");
        }
        requireUuid(token, "processingToken");
        requireLease(startedAt, leaseUntil);
        processingToken = token;
        processingStartedAt = startedAt;
        processingLeaseUntil = leaseUntil;
        attemptCount = Math.incrementExact(attemptCount);
        reasonCode = null;
        nextAttemptAt = null;
        updatedAt = startedAt;
    }

    public void approve(String token, Instant processedAt) {
        requireOwnedProcessing(token, processedAt);
        status = SubscriptionVerificationStatus.APPROVED;
        activeGuard = null;
        approvedGuard = (byte) 1;
        reasonCode = null;
        rewardStatus = VerificationRewardStatus.PENDING;
        finishProcessing(processedAt);
    }

    public void reject(String token, String reasonCode, Instant processedAt) {
        finishWithoutApproval(token, SubscriptionVerificationStatus.REJECTED, reasonCode, processedAt);
    }

    public void requireRetry(String token, String reasonCode, Instant processedAt) {
        finishWithoutApproval(token, SubscriptionVerificationStatus.RETRY_REQUIRED, reasonCode, processedAt);
    }

    public void fail(String token, String reasonCode, Instant processedAt) {
        finishWithoutApproval(token, SubscriptionVerificationStatus.FAILED, reasonCode, processedAt);
    }

    public void acceptReward(Instant acceptedAt) {
        requireApprovedRewardState(acceptedAt);
        rewardStatus = VerificationRewardStatus.ACCEPTED;
        nextAttemptAt = null;
        updatedAt = acceptedAt;
    }

    public void requireRewardRetry(Instant nextAttemptAt, Instant failedAt) {
        requireApprovedRewardState(failedAt);
        if (nextAttemptAt == null || !nextAttemptAt.isAfter(failedAt)) {
            throw new IllegalArgumentException("다음 보상 시도 시각은 실패 시각보다 늦어야 합니다.");
        }
        rewardStatus = VerificationRewardStatus.RETRY_REQUIRED;
        this.nextAttemptAt = nextAttemptAt;
        updatedAt = failedAt;
    }

    private void finishWithoutApproval(
            String token,
            SubscriptionVerificationStatus terminalStatus,
            String reasonCode,
            Instant processedAt
    ) {
        requireOwnedProcessing(token, processedAt);
        requireText(reasonCode, "reasonCode", 50);
        status = terminalStatus;
        activeGuard = null;
        approvedGuard = null;
        this.reasonCode = reasonCode;
        finishProcessing(processedAt);
    }

    private void finishProcessing(Instant processedAt) {
        this.processedAt = processedAt;
        processingLeaseUntil = null;
        nextAttemptAt = null;
        updatedAt = processedAt;
    }

    private void requireOwnedProcessing(String token, Instant processedAt) {
        requireStatus(SubscriptionVerificationStatus.PROCESSING);
        requireUuid(token, "processingToken");
        if (!Objects.equals(processingToken, token)) {
            throw new IllegalStateException("처리 소유권이 일치하지 않습니다.");
        }
        Objects.requireNonNull(processedAt, "processedAt은 필수입니다.");
        if (processingStartedAt != null && processedAt.isBefore(processingStartedAt)) {
            throw new IllegalArgumentException("처리 완료 시각은 시작 시각보다 빠를 수 없습니다.");
        }
        if (processingLeaseUntil == null || !processingLeaseUntil.isAfter(processedAt)) {
            throw new IllegalStateException("처리 lease가 만료되었습니다.");
        }
    }

    private void requireApprovedRewardState(Instant changedAt) {
        if (status != SubscriptionVerificationStatus.APPROVED
                || (rewardStatus != VerificationRewardStatus.PENDING
                && rewardStatus != VerificationRewardStatus.RETRY_REQUIRED)) {
            throw new IllegalStateException("보상을 처리할 수 있는 인증 상태가 아닙니다.");
        }
        Objects.requireNonNull(changedAt, "보상 상태 변경 시각은 필수입니다.");
    }

    private void requireStatus(SubscriptionVerificationStatus expected) {
        if (status != expected) {
            throw new IllegalStateException("허용되지 않은 구독 인증 상태 전이입니다.");
        }
    }

    private static void requireLease(Instant startedAt, Instant leaseUntil) {
        Objects.requireNonNull(startedAt, "processingStartedAt은 필수입니다.");
        if (leaseUntil == null || !leaseUntil.isAfter(startedAt)) {
            throw new IllegalArgumentException("processingLeaseUntil은 시작 시각보다 늦어야 합니다.");
        }
    }

    private static void requireUuid(String value, String name) {
        if (value == null || !UUID_PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException(name + "는 UUID 형식이어야 합니다.");
        }
    }

    private static void requireSha256(String value, String name) {
        if (value == null || !SHA256_PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException(name + "는 SHA-256 lowercase hex 형식이어야 합니다.");
        }
    }

    private static void requireText(String value, String name, int maxLength) {
        if (value == null || value.isBlank() || value.length() > maxLength) {
            throw new IllegalArgumentException(name + "는 필수이며 " + maxLength + "자 이하여야 합니다.");
        }
    }
}
