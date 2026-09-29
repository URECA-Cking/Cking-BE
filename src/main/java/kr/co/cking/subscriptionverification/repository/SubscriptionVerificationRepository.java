package kr.co.cking.subscriptionverification.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import kr.co.cking.subscriptionverification.domain.SubscriptionVerification;
import kr.co.cking.subscriptionverification.domain.SubscriptionVerificationStatus;
import kr.co.cking.subscriptionverification.domain.VerificationRewardStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Pageable;

public interface SubscriptionVerificationRepository extends JpaRepository<SubscriptionVerification, Long> {

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE subscription_verification
            SET status = 'PROCESSING',
                processing_token = :processingToken,
                processing_started_at = :startedAt,
                processing_lease_until = :leaseUntil,
                attempt_count = attempt_count + 1,
                reason_code = NULL,
                next_attempt_at = NULL,
                updated_at = :startedAt,
                version = version + 1
            WHERE verification_id = :verificationId
              AND (
                  status = 'PENDING'
                  OR (status = 'PROCESSING' AND processing_lease_until <= :startedAt)
              )
            """, nativeQuery = true)
    int claimProcessing(
            @Param("verificationId") Long verificationId,
            @Param("processingToken") String processingToken,
            @Param("startedAt") Instant startedAt,
            @Param("leaseUntil") Instant leaseUntil
    );

    Optional<SubscriptionVerification> findByVerificationIdAndStatusAndProcessingToken(
            Long verificationId,
            SubscriptionVerificationStatus status,
            String processingToken
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update SubscriptionVerification v
               set v.status = :finalStatus,
                   v.reasonCode = :reasonCode,
                   v.rewardStatus = :rewardStatus,
                   v.activeGuard = null,
                   v.approvedGuard = :approvedGuard,
                   v.processingLeaseUntil = null,
                   v.nextAttemptAt = null,
                   v.processedAt = :processedAt,
                   v.updatedAt = :processedAt,
                   v.version = v.version + 1
             where v.verificationId = :verificationId
               and v.status = :processingStatus
               and v.processingToken = :processingToken
               and v.processingStartedAt <= :processedAt
               and v.processingLeaseUntil > :processedAt
            """)
    int completeProcessing(
            @Param("verificationId") Long verificationId,
            @Param("processingToken") String processingToken,
            @Param("processingStatus") SubscriptionVerificationStatus processingStatus,
            @Param("finalStatus") SubscriptionVerificationStatus finalStatus,
            @Param("reasonCode") String reasonCode,
            @Param("rewardStatus") VerificationRewardStatus rewardStatus,
            @Param("approvedGuard") Byte approvedGuard,
            @Param("processedAt") Instant processedAt
    );

    Optional<SubscriptionVerification> findByRequestId(String requestId);

    Optional<SubscriptionVerification> findByMemberIdAndCreatorIdAndMissionIdAndStatus(
            Long memberId,
            Long creatorId,
            Long missionId,
            SubscriptionVerificationStatus status
    );

    boolean existsByMemberIdAndCreatorIdAndMissionIdAndStatusIn(
            Long memberId,
            Long creatorId,
            Long missionId,
            Collection<SubscriptionVerificationStatus> statuses
    );

    boolean existsByCreatorIdAndStatusIn(
            Long creatorId,
            Collection<SubscriptionVerificationStatus> statuses
    );

    Optional<SubscriptionVerification> findFirstByMemberIdAndCreatorIdAndMissionIdOrderByCreatedAtDescVerificationIdDesc(
            Long memberId,
            Long creatorId,
            Long missionId
    );

    /** 현재 행을 제외한 같은 이미지 hash 이력을 생성 시각·식별자 순으로 조회한다. */
    @Lock(jakarta.persistence.LockModeType.PESSIMISTIC_READ)
    @Query("""
            select verification
            from SubscriptionVerification verification
            where verification.imageSha256 = :imageSha256
              and verification.verificationId <> :verificationId
            order by verification.createdAt asc, verification.verificationId asc
            """)
    List<SubscriptionVerification> findPreviousByImageSha256(
            @Param("imageSha256") String imageSha256,
            @Param("verificationId") Long verificationId,
            Pageable pageable
    );

    long countByMemberIdAndCreatorIdAndMissionIdAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(
            Long memberId,
            Long creatorId,
            Long missionId,
            Instant from,
            Instant to
    );

    List<SubscriptionVerification> findAllByMemberIdAndCreatorIdAndMissionId(
            Long memberId,
            Long creatorId,
            Long missionId
    );
}
