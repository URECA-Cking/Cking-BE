package kr.co.cking.abuse.infrastructure.persistence;

import jakarta.persistence.LockModeType;
import kr.co.cking.abuse.domain.AbuseDetectionStatus;
import kr.co.cking.abuse.domain.AbuseType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

/** abuse_detection 전용 Spring Data JPA 접근을 제공한다. */
interface AbuseDetectionJpaRepository extends JpaRepository<AbuseDetectionJpaEntity, Long> {

    /** 조건부 UPDATE가 0건일 때 최신 커밋 상태를 확인하도록 행을 공유 잠금으로 현재 읽기한다. */
    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("select detection from AbuseDetectionJpaEntity detection where detection.id = :detectionId")
    Optional<AbuseDetectionJpaEntity> findByIdForShare(@Param("detectionId") Long detectionId);

    /** 선택한 회원·유형·상태·탐지 기간 조건에 맞는 Detection 페이지를 조회한다. */
    @Query("""
            select detection
            from AbuseDetectionJpaEntity detection
            where (:memberId is null or detection.memberId = :memberId)
              and (:abuseType is null or detection.abuseType = :abuseType)
              and (:status is null or detection.status = :status)
              and (:detectedAtFrom is null or detection.detectedAt >= :detectedAtFrom)
              and (:detectedAtTo is null or detection.detectedAt <= :detectedAtTo)
            """)
    Page<AbuseDetectionJpaEntity> search(
            @Param("memberId") Long memberId,
            @Param("abuseType") AbuseType abuseType,
            @Param("status") AbuseDetectionStatus status,
            @Param("detectedAtFrom") Instant detectedAtFrom,
            @Param("detectedAtTo") Instant detectedAtTo,
            Pageable pageable
    );

    /** 아직 검토되지 않은 Detection 한 건만 목표 검토 상태와 검토 정보로 전이한다. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update AbuseDetectionJpaEntity detection
            set detection.status = :targetStatus,
                detection.reviewedAt = :reviewedAt,
                detection.reviewedBy = :reviewedBy
            where detection.id = :detectionId
              and detection.status = kr.co.cking.abuse.domain.AbuseDetectionStatus.DETECTED
            """)
    int reviewIfDetected(
            @Param("detectionId") Long detectionId,
            @Param("targetStatus") AbuseDetectionStatus targetStatus,
            @Param("reviewedBy") Long reviewedBy,
            @Param("reviewedAt") Instant reviewedAt
    );
}
