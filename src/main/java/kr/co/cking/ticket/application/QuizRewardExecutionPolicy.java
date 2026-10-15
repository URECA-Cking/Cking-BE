package kr.co.cking.ticket.application;

import kr.co.cking.ticket.application.dto.QuizEarnEvent;
import kr.co.cking.ticket.domain.TicketQuizRewardRequest;

/**
 * Quiz EARN 메시지가 저장된 요청의 Redis 실행 결과로 받아들일 수 있는지 판단한다(이슈 #533).
 *
 * <p>운영 구현은 저장된 실행 기록과 대조하는 {@link StoredExecutionQuizRewardExecutionPolicy}다. 구현은 DB 반영
 * Transaction 안에서 Quiz 요청을 잠근 뒤 호출되며 Redis를 호출하지 않는다.
 */
public interface QuizRewardExecutionPolicy {

    /**
     * 이미 APPLIED가 아닌 요청에 대해 호출한다. 받아들일 수 없으면 {@link IllegalStateException}을 던져 반영을 막는다.
     */
    void requireApplicable(TicketQuizRewardRequest request, QuizEarnEvent event);
}
