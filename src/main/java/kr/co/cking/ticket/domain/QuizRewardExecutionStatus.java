package kr.co.cking.ticket.domain;

/** Quiz 보상 Redis EARN 실행 한 번의 상태(이슈 #533). */
public enum QuizRewardExecutionStatus {

    /** 실행권을 발급했다. Redis 결과는 아직 기록하지 않았다. */
    STARTED,
    /** Redis 결과를 기록했다. */
    FINISHED
}
