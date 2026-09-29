package kr.co.cking.subscriptionverification.domain;

import static kr.co.cking.common.validation.DomainValidator.requirePositive;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** Verification별 이미지 재사용 탐지 결과와 이전 매칭 대상을 보존한다. */
@Getter
@Entity
@Table(name = "subscription_verification_image_reuse")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SubscriptionVerificationImageReuse {

    @Id
    @Column(name = "verification_id")
    private Long verificationId;

    @Column(name = "matched_verification_id")
    private Long matchedVerificationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "reuse_type", nullable = false, length = 40)
    private SubscriptionVerificationImageReuseType reuseType;

    @Column(name = "detected_at", nullable = false, updatable = false)
    private Instant detectedAt;

    /** 최초 또는 재사용 탐지 결과를 이전 Verification 식별자와 함께 생성한다. */
    public SubscriptionVerificationImageReuse(
            Long verificationId,
            Long matchedVerificationId,
            SubscriptionVerificationImageReuseType reuseType,
            Instant detectedAt
    ) {
        requirePositive(verificationId, "verificationId");
        if (matchedVerificationId != null) {
            requirePositive(matchedVerificationId, "matchedVerificationId");
        }
        this.reuseType = Objects.requireNonNull(reuseType, "reuseType은 필수입니다.");
        if ((reuseType == SubscriptionVerificationImageReuseType.FIRST_USE) != (matchedVerificationId == null)) {
            throw new IllegalArgumentException("최초 이미지는 이전 Verification 없이 기록해야 합니다.");
        }
        this.verificationId = verificationId;
        this.matchedVerificationId = matchedVerificationId;
        this.detectedAt = Objects.requireNonNull(detectedAt, "detectedAt은 필수입니다.");
    }
}
