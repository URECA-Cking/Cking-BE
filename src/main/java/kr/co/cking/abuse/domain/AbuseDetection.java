package kr.co.cking.abuse.domain;

import static kr.co.cking.common.validation.DomainValidator.requirePositive;

import java.time.Instant;
import java.util.Objects;

/** Detection 영속화 Adapter와 관리자 검토가 공유하는 순수 Domain Aggregate다. */
public final class AbuseDetection {

    private final Long detectionId;
    private final Long memberId;
    private final AbuseType abuseType;
    private final Instant detectedAt;
    private final DetectionEvidence evidence;
    private AbuseDetectionStatus status;
    private Instant reviewedAt;
    private Long reviewedBy;

    private AbuseDetection(
            Long detectionId,
            Long memberId,
            AbuseType abuseType,
            AbuseDetectionStatus status,
            Instant detectedAt,
            Instant reviewedAt,
            Long reviewedBy,
            DetectionEvidence evidence
    ) {
        if (detectionId != null) {
            requirePositive(detectionId, "detectionId");
        }
        requirePositive(memberId, "memberId");
        this.detectionId = detectionId;
        this.memberId = memberId;
        this.abuseType = Objects.requireNonNull(abuseType, "abuseType은 필수입니다.");
        this.status = Objects.requireNonNull(status, "status는 필수입니다.");
        this.detectedAt = Objects.requireNonNull(detectedAt, "detectedAt은 필수입니다.");
        this.evidence = Objects.requireNonNull(evidence, "evidence는 필수입니다.");
        validateReview(status, reviewedAt, reviewedBy);
        this.reviewedAt = reviewedAt;
        this.reviewedBy = reviewedBy;
    }

    public static AbuseDetection detected(Long memberId, DetectionResult result) {
        Objects.requireNonNull(result, "result는 필수입니다.");
        return new AbuseDetection(
                null,
                memberId,
                result.abuseType(),
                AbuseDetectionStatus.DETECTED,
                result.detectedAt(),
                null,
                null,
                result.evidence()
        );
    }

    /** Adapter가 저장된 상태를 복원할 때 사용한다. */
    public static AbuseDetection restore(
            Long detectionId,
            Long memberId,
            AbuseType abuseType,
            AbuseDetectionStatus status,
            Instant detectedAt,
            Instant reviewedAt,
            Long reviewedBy,
            DetectionEvidence evidence
    ) {
        return new AbuseDetection(
                detectionId, memberId, abuseType, status, detectedAt, reviewedAt, reviewedBy, evidence);
    }

    /** 단일 객체 사용 시의 상태 불변식이며, DB 동시 전이는 Repository 조건부 UPDATE가 최종 방어한다. */
    public void review(AbuseReviewDecision decision, Long adminId, Instant now) {
        Objects.requireNonNull(decision, "decision은 필수입니다.");
        requirePositive(adminId, "adminId");
        Objects.requireNonNull(now, "now는 필수입니다.");
        AbuseDetectionStatus targetStatus = decision.toStatus();
        if (status == targetStatus) {
            return;
        }
        if (status != AbuseDetectionStatus.DETECTED) {
            throw new IllegalStateException("이미 반대 결과로 검토된 Detection은 재판정할 수 없습니다.");
        }
        status = targetStatus;
        reviewedAt = now;
        reviewedBy = adminId;
    }

    private static void validateReview(
            AbuseDetectionStatus status,
            Instant reviewedAt,
            Long reviewedBy
    ) {
        if (status == AbuseDetectionStatus.DETECTED) {
            if (reviewedAt != null || reviewedBy != null) {
                throw new IllegalArgumentException("DETECTED 상태에는 검토 정보를 지정할 수 없습니다.");
            }
            return;
        }
        Objects.requireNonNull(reviewedAt, "검토 완료 상태의 reviewedAt은 필수입니다.");
        requirePositive(reviewedBy, "reviewedBy");
    }

    public Long detectionId() {
        return detectionId;
    }

    public Long memberId() {
        return memberId;
    }

    public AbuseType abuseType() {
        return abuseType;
    }

    public AbuseDetectionStatus status() {
        return status;
    }

    public Instant detectedAt() {
        return detectedAt;
    }

    public Instant reviewedAt() {
        return reviewedAt;
    }

    public Long reviewedBy() {
        return reviewedBy;
    }

    public DetectionEvidence evidence() {
        return evidence;
    }
}
