package kr.co.cking.ticket.repository;

import kr.co.cking.ticket.domain.TicketQuizRewardRequest;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface TicketQuizRewardRequestRepository extends JpaRepository<TicketQuizRewardRequest, Long> {

    Optional<TicketQuizRewardRequest> findByRewardRequestId(String rewardRequestId);

    Optional<TicketQuizRewardRequest> findByAttemptId(Long attemptId);
}
