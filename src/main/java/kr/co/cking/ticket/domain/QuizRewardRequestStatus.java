package kr.co.cking.ticket.domain;

/**
 * Quiz 보상 지급 요청 상태(이슈 #533). 접수는 원장 반영 완료가 아니다.
 *
 * <p>지금은 접수({@link #RECEIVED})만 구현했다. 나머지 상태로의 전이와 Redis·Stream·원장 처리는 후속 단계에서
 * 구현하며, 어느 상태에서도 복구는 새 rewardRequestId를 만들지 않고 같은 요청으로 처리 여부를 확인한다.
 */
public enum QuizRewardRequestStatus {

    /** 접수했다. 아직 Redis EARN을 실행하지 않았다. */
    RECEIVED,
    /** Redis EARN 실행을 시작했다. 결과는 아직 확인하지 않았다. */
    EXECUTING,
    /** Redis가 EARN을 받아들였다. Ticket 원장·잔액에는 아직 반영되지 않았을 수 있다. */
    ACCEPTED,
    /** Ticket 원장·잔액에 반영했다. */
    APPLIED,
    /** 실행 결과를 확인할 수 없다. 같은 요청으로 처리 여부를 확인해야 한다. */
    UNKNOWN,
    /** 반영되지 않은 것이 확인된 실패다. 같은 요청으로 다시 시도할 수 있다. */
    RETRYABLE_FAILURE
}
