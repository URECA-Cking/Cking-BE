package kr.co.cking.ticket.repository;

import kr.co.cking.ticket.domain.TicketQuizRewardRequest;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface TicketQuizRewardRequestRepository extends JpaRepository<TicketQuizRewardRequest, Long> {

    Optional<TicketQuizRewardRequest> findByRewardRequestId(String rewardRequestId);

    Optional<TicketQuizRewardRequest> findByAttemptId(Long attemptId);

    /** EARN 반영 때 전역 EARN request 다음에 잠근다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from TicketQuizRewardRequest r where r.rewardRequestId = :rewardRequestId")
    Optional<TicketQuizRewardRequest> findByRewardRequestIdForUpdate(@Param("rewardRequestId") String rewardRequestId);
}
