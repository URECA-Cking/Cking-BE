package kr.co.cking.ticket.application.dto;

/**
 * {@code quiz-ticket-earn.lua} 실행 결과의 분류(이슈 #533). Redis에서 적립·발행이 됐는지만 말하며, DB 원장 반영
 * (APPLIED)은 어느 값도 뜻하지 않는다.
 */
public enum QuizRedisEarnOutcome {

    /** 이번 실행이 Redis Balance를 늘리고 Stream에 발행했다. */
    ACCEPTED(false, false),
    /** 같은 요청이 이미 Redis에서 적립·발행됐다. 다시 적립하지 않았다. */
    ALREADY_ACCEPTED(false, false),
    /** 같은 rewardRequestId에 다른 내용이 이미 기록돼 있다. 아무것도 바꾸지 않았다. */
    REQUEST_ID_CONFLICT(true, false),
    /** 수동 보정 락이 걸려 있다. 아무것도 바꾸지 않았고 락이 풀린 뒤 다시 시도할 수 있다. */
    BALANCE_MAINTENANCE(true, true),
    /** Redis Balance 키가 없다. 아무것도 바꾸지 않았고 키를 적재한 뒤 다시 시도할 수 있다. */
    BALANCE_NOT_LOADED(true, true),
    /** Lua 입력 계약 위반이다. 아무것도 바꾸지 않았고 같은 입력으로 다시 시도해도 같다. */
    INVALID_REQUEST(true, false),
    /** Redis 쓰기가 실패했지만 모든 변경을 되돌린 것을 스크립트가 확인했다. 같은 요청으로 다시 시도할 수 있다. */
    RETRYABLE_FAILURE(true, true),
    /** 적립·발행 여부를 확정할 수 없다. 같은 요청을 자동으로 다시 실행하지 않는다. */
    UNKNOWN(false, false);

    private final boolean confirmedNotExecuted;
    private final boolean retryable;

    QuizRedisEarnOutcome(boolean confirmedNotExecuted, boolean retryable) {
        this.confirmedNotExecuted = confirmedNotExecuted;
        this.retryable = retryable;
    }

    /** 이번 실행이 Redis를 바꾸지 않았음이 확인됐는지. */
    public boolean confirmedNotExecuted() {
        return confirmedNotExecuted;
    }

    /** 같은 rewardRequestId로 다시 실행해도 중복 적립이 생기지 않는 확인된 실패인지. */
    public boolean retryable() {
        return retryable;
    }
}
