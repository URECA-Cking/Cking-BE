package kr.co.cking.ticket.repository;

import jakarta.persistence.LockModeType;
import kr.co.cking.ticket.domain.TicketQuizRewardExecution;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

import java.util.Optional;

public interface TicketQuizRewardExecutionRepository extends JpaRepository<TicketQuizRewardExecution, Long> {

    /** 가장 최근 실행. 전역 EARN request → Quiz 요청 다음에 잠근다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<TicketQuizRewardExecution> findFirstByQuizRewardRequestIdOrderByExecutionSeqDesc(Long quizRewardRequestId);

    /** 실행권으로 실행을 찾는다. 결과 기록 때 전역 EARN request → Quiz 요청 다음에 잠근다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<TicketQuizRewardExecution> findByExecutionToken(String executionToken);
}
