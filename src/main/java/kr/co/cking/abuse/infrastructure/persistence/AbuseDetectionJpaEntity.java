package kr.co.cking.abuse.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import kr.co.cking.abuse.domain.AbuseDetectionStatus;
import kr.co.cking.abuse.domain.AbuseType;

import java.time.Instant;

/** 순수 Domain Aggregate와 분리되어 abuse_detection 테이블 한 행을 표현하는 JPA Entity다. */
@Entity
@Table(name = "abuse_detection")
class AbuseDetectionJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "member_id", nullable = false)
    private Long memberId;

    @Enumerated(EnumType.STRING)
    @Column(name = "abuse_type", nullable = false, length = 64)
    private AbuseType abuseType;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private AbuseDetectionStatus status;

    @Column(name = "detected_at", nullable = false)
    private Instant detectedAt;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    @Column(name = "reviewed_by")
    private Long reviewedBy;

    @Column(name = "evidence", nullable = false, columnDefinition = "json")
    private String evidenceJson;

    /** JPA가 DB 행을 복원할 때만 사용하는 기본 생성자다. */
    protected AbuseDetectionJpaEntity() {
    }

    /** Domain 값을 DB 저장용 Entity로 구성한다. */
    static AbuseDetectionJpaEntity create(
            Long memberId,
            AbuseType abuseType,
            AbuseDetectionStatus status,
            Instant detectedAt,
            Instant reviewedAt,
            Long reviewedBy,
            String evidenceJson
    ) {
        AbuseDetectionJpaEntity entity = new AbuseDetectionJpaEntity();
        entity.memberId = memberId;
        entity.abuseType = abuseType;
        entity.status = status;
        entity.detectedAt = detectedAt;
        entity.reviewedAt = reviewedAt;
        entity.reviewedBy = reviewedBy;
        entity.evidenceJson = evidenceJson;
        return entity;
    }

    /** 저장 후 할당된 Detection 식별자를 반환한다. */
    Long id() {
        return id;
    }

    /** 탐지 대상 회원 식별자를 반환한다. */
    Long memberId() {
        return memberId;
    }

    /** 영속화한 비정상 행동 유형을 반환한다. */
    AbuseType abuseType() {
        return abuseType;
    }

    /** 현재 관리자 검토 상태를 반환한다. */
    AbuseDetectionStatus status() {
        return status;
    }

    /** 탐지가 확정된 UTC 시각을 반환한다. */
    Instant detectedAt() {
        return detectedAt;
    }

    /** 검토가 완료된 UTC 시각을 반환한다. */
    Instant reviewedAt() {
        return reviewedAt;
    }

    /** 검토를 수행한 관리자 식별자를 반환한다. */
    Long reviewedBy() {
        return reviewedBy;
    }

    /** JSON 컬럼에 저장된 Evidence 문자열을 반환한다. */
    String evidenceJson() {
        return evidenceJson;
    }
}
