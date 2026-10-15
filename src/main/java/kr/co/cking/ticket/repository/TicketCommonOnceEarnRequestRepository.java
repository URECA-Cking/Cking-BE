package kr.co.cking.ticket.repository;

import kr.co.cking.ticket.domain.TicketCommonOnceEarnRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface TicketCommonOnceEarnRequestRepository extends JpaRepository<TicketCommonOnceEarnRequest, Long> {
    /** 동일 requestId의 영속 선점 기록을 조회한다. */
    Optional<TicketCommonOnceEarnRequest> findByRequestId(String requestId);

    /** 회원과 보상 종류의 평생 지급 원인을 조회한다. */
    Optional<TicketCommonOnceEarnRequest> findByMemberIdAndRewardType(Long memberId, String rewardType);

    /** 상태 조회에 필요한 해당 회원의 승인 기록만 가져온다. */
    List<TicketCommonOnceEarnRequest> findByMemberIdAndStatus(Long memberId, kr.co.cking.ticket.domain.TicketOnceEarnRequestStatus status);

    /** UNIQUE 선점 경합은 기다린 뒤 무시하고 호출자가 충돌 원인을 재조회하게 한다. */
    @Modifying
    @Query(value = """
            INSERT IGNORE INTO ticket_common_once_earn_request
                (request_id, member_id, reward_type, amount, payload_fingerprint, status, created_at)
            VALUES (:requestId, :memberId, :rewardType, :amount, :fingerprint, 'PENDING', :createdAt)
            """, nativeQuery = true)
    int insertIgnore(@Param("requestId") String requestId, @Param("memberId") Long memberId,
                     @Param("rewardType") String rewardType, @Param("amount") Long amount,
                     @Param("fingerprint") String fingerprint, @Param("createdAt") Instant createdAt);

    /** 최초 Redis 실행권을 한 요청자에게만 발급한다. */
    @Modifying
    @Query("update TicketCommonOnceEarnRequest r set r.status = :next where r.requestId = :requestId and r.status = :current")
    int changeStatus(@Param("requestId") String requestId,
                     @Param("current") kr.co.cking.ticket.domain.TicketOnceEarnRequestStatus current,
                     @Param("next") kr.co.cking.ticket.domain.TicketOnceEarnRequestStatus next);
}
