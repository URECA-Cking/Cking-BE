package kr.co.cking.ticket.application;

import kr.co.cking.ticket.application.QuizRewardExecutionStore.FinishOutcome;
import kr.co.cking.ticket.application.dto.QuizRedisEarnOutcome;

/**
 * Quiz 보상 Worker 한 번의 결과(이슈 #533). Redis 실행 여부와 결과 기록 여부를 함께 알려준다. 원장 반영(APPLIED)과는
 * 별개다.
 *
 * @param claimOutcome  실행권 발급 결과. 발급을 시도하지 않았으면 null
 * @param redisOutcome  Redis 실행 결과 분류(Lua를 부르지 않고 무반영으로 정한 경우 포함). Redis 단계에 가지 않았으면 null
 * @param finishOutcome 결과 기록 결과. RECORDED 상태에서만 있다
 */
public record QuizRewardWorkResult(Status status, QuizRewardExecutionClaim.Outcome claimOutcome,
                                   QuizRedisEarnOutcome redisOutcome, FinishOutcome finishOutcome) {

    public QuizRewardWorkResult {
        if (status == null) {
            throw new IllegalArgumentException("Worker 결과가 필요합니다.");
        }
        boolean valid = switch (status) {
            case DISABLED, REQUEST_NOT_FOUND -> claimOutcome == null && redisOutcome == null && finishOutcome == null;
            case NOT_CLAIMED -> claimOutcome != null && claimOutcome != QuizRewardExecutionClaim.Outcome.CLAIMED
                    && redisOutcome == null && finishOutcome == null;
            case RECORDED -> claimOutcome == QuizRewardExecutionClaim.Outcome.CLAIMED && redisOutcome != null
                    && finishOutcome != null;
            case RECORD_FAILED -> claimOutcome == QuizRewardExecutionClaim.Outcome.CLAIMED && redisOutcome != null
                    && finishOutcome == null;
        };
        if (!valid) {
            throw new IllegalArgumentException("Worker 결과와 단계별 값이 맞지 않습니다. status=" + status);
        }
    }

    public enum Status {
        /** Worker가 꺼져 있어 아무것도 하지 않았다. */
        DISABLED,
        /** 접수된 요청이 없다. */
        REQUEST_NOT_FOUND,
        /** 실행권을 얻지 못해 Redis를 부르지 않았다. */
        NOT_CLAIMED,
        /** Redis 단계 결과를 기록 Transaction에 넘겼다. 기록 결과는 finishOutcome이다. */
        RECORDED,
        /**
         * Redis 단계 뒤 결과 기록이 실패했다. 요청은 EXECUTING, 실행은 결과 없이 남아 같은 요청을 다시 실행하지 않는다.
         */
        RECORD_FAILED
    }
}
