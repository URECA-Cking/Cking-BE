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

    /**
     * 같은 회원·Creator에 Redis 적립 여부가 아직 DB 원장으로 확정되지 않은 Quiz 보상이 몇 건인지 센다. 원장 반영(APPLIED)이
     * 끝난 요청은 확정이라 제외한다. 나머지 요청 중 아래 하나라도 해당하면 센다.
     * <ul>
     *   <li>현재 요청이 아니고 EXECUTING·ACCEPTED·UNKNOWN이다</li>
     *   <li>현재 실행권이 아닌 실행 중에 결과가 없거나, 결과가 무반영으로 확인되지 않은 실행이 있다(현재 요청의 과거 실행
     *       포함)</li>
     * </ul>
     */
    @Query(value = """
            SELECT COUNT(*)
            FROM ticket_quiz_reward_request r
            WHERE r.member_id = :memberId
              AND r.creator_id = :creatorId
              AND r.status <> 'APPLIED'
              AND ((r.quiz_reward_request_id <> :currentRequestId AND r.status IN ('EXECUTING', 'ACCEPTED', 'UNKNOWN'))
                   OR EXISTS (SELECT 1
                              FROM ticket_quiz_reward_execution e
                              WHERE e.quiz_reward_request_id = r.quiz_reward_request_id
                                AND e.execution_token <> :currentExecutionToken
                                AND NOT (e.status = 'FINISHED' AND e.not_executed_confirmed = TRUE)))
            """, nativeQuery = true)
    long countUnsettled(@Param("memberId") Long memberId, @Param("creatorId") Long creatorId,
                        @Param("currentRequestId") Long currentRequestId,
                        @Param("currentExecutionToken") String currentExecutionToken);
}
