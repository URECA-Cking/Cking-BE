package kr.co.cking.ticket.repository;

import kr.co.cking.ticket.domain.TicketOnceEarnRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface TicketOnceEarnRequestRepository extends JpaRepository<TicketOnceEarnRequest, Long> {

    Optional<TicketOnceEarnRequest> findByRequestId(String requestId);

    Optional<TicketOnceEarnRequest> findByMemberIdAndCreatorIdAndMissionId(Long memberId, Long creatorId, Long missionId);

    /** INSERT IGNORE 충돌 뒤 최신 커밋 행을 잠금 현재 읽기로 조회한다. */
    @Query(value = "SELECT * FROM ticket_once_earn_request WHERE request_id = :requestId FOR UPDATE", nativeQuery = true)
    Optional<TicketOnceEarnRequest> findByRequestIdForUpdate(@Param("requestId") String requestId);

    /** 같은 평생 보상 Business Key의 최신 커밋 행을 잠금 현재 읽기로 조회한다. */
    @Query(value = """
            SELECT * FROM ticket_once_earn_request
            WHERE member_id = :memberId AND creator_id = :creatorId AND mission_id = :missionId
            FOR UPDATE
            """, nativeQuery = true)
    Optional<TicketOnceEarnRequest> findByMemberIdAndCreatorIdAndMissionIdForUpdate(
            @Param("memberId") Long memberId,
            @Param("creatorId") Long creatorId,
            @Param("missionId") Long missionId
    );

    @Modifying
    @Query(value = """
            INSERT IGNORE INTO ticket_once_earn_request
                (request_id, member_id, creator_id, mission_id, mission_type, amount, period_key,
                 payload_fingerprint, status, created_at, accepted_at)
            VALUES (:requestId, :memberId, :creatorId, :missionId, :missionType, :amount, :periodKey,
                    :payloadFingerprint, 'PENDING', :createdAt, NULL)
            """, nativeQuery = true)
    int insertIgnore(
            @Param("requestId") String requestId,
            @Param("memberId") Long memberId,
            @Param("creatorId") Long creatorId,
            @Param("missionId") Long missionId,
            @Param("missionType") String missionType,
            @Param("amount") Long amount,
            @Param("periodKey") String periodKey,
            @Param("payloadFingerprint") String payloadFingerprint,
            @Param("createdAt") Instant createdAt
    );
}
