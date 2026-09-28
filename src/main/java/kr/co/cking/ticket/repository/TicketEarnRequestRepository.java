package kr.co.cking.ticket.repository;

import kr.co.cking.ticket.domain.TicketEarnRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface TicketEarnRequestRepository extends JpaRepository<TicketEarnRequest, Long> {

    Optional<TicketEarnRequest> findByRequestId(String requestId);

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
}
