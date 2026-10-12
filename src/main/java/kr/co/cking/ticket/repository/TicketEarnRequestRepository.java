package kr.co.cking.ticket.repository;

import kr.co.cking.ticket.domain.TicketEarnRequest;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface TicketEarnRequestRepository extends JpaRepository<TicketEarnRequest, Long> {

    Optional<TicketEarnRequest> findByRequestId(String requestId);

    /** Quiz 보상 EARN 반영 때 가장 먼저 잠근다(전역 EARN request → Quiz 요청 → Balance 순서). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from TicketEarnRequest r where r.requestId = :requestId")
    Optional<TicketEarnRequest> findByRequestIdForUpdate(@Param("requestId") String requestId);

    @Modifying
    @Query(value = """
            INSERT IGNORE INTO ticket_earn_request
                (request_id, payload_fingerprint, reward_policy, status, created_at, accepted_at)
            VALUES (:requestId, :payloadFingerprint, :rewardPolicy, 'PENDING', :createdAt, NULL)
            """, nativeQuery = true)
    int insertIgnore(
            @Param("requestId") String requestId,
            @Param("payloadFingerprint") String payloadFingerprint,
            @Param("rewardPolicy") String rewardPolicy,
            @Param("createdAt") Instant createdAt
    );

    /**
     * 같은 requestId가 있으면 무시하지 않고 고유 제약 예외를 낸다. 다른 기록과 한 Transaction으로 선점해야 할 때 쓰며,
     * 예외가 나면 그 Transaction 전체를 롤백한다. 기존 Mission 선점은 {@link #insertIgnore}를 그대로 쓴다.
     */
    @Modifying
    @Query(value = """
            INSERT INTO ticket_earn_request
                (request_id, payload_fingerprint, reward_policy, status, created_at, accepted_at)
            VALUES (:requestId, :payloadFingerprint, :rewardPolicy, 'PENDING', :createdAt, NULL)
            """, nativeQuery = true)
    int insertPending(
            @Param("requestId") String requestId,
            @Param("payloadFingerprint") String payloadFingerprint,
            @Param("rewardPolicy") String rewardPolicy,
            @Param("createdAt") Instant createdAt
    );
}
