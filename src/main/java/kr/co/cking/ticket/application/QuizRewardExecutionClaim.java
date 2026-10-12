package kr.co.cking.ticket.application;

/**
 * Quiz 보상 실행권 발급 결과(이슈 #533). 실행권을 얻은 경우에만 실행 정보를 담는다. 다른 실행의 실행권은 돌려주지 않는다.
 *
 * @param quizRewardRequestId 실행권을 얻은 요청 ID
 * @param executionToken      이번 실행권. Redis 실행 입력의 executionToken으로 그대로 쓴다
 * @param executionSeq        이번 실행 순번(1부터)
 */
public record QuizRewardExecutionClaim(Outcome outcome, Long quizRewardRequestId, String executionToken,
                                       Integer executionSeq) {

    /** CLAIMED는 실행 정보가 모두 있어야 하고, 그 밖에는 모두 없어야 한다. */
    public QuizRewardExecutionClaim {
        if (outcome == null) {
            throw new IllegalArgumentException("실행권 발급 결과가 필요합니다.");
        }
        boolean valid = outcome == Outcome.CLAIMED
                ? quizRewardRequestId != null && executionToken != null && executionSeq != null && executionSeq > 0
                : quizRewardRequestId == null && executionToken == null && executionSeq == null;
        if (!valid) {
            throw new IllegalArgumentException("실행권 발급 결과와 실행 정보가 맞지 않습니다. outcome=" + outcome);
        }
    }

    public static QuizRewardExecutionClaim claimed(Long quizRewardRequestId, String executionToken, int executionSeq) {
        return new QuizRewardExecutionClaim(Outcome.CLAIMED, quizRewardRequestId, executionToken, executionSeq);
    }

    public static QuizRewardExecutionClaim rejected(Outcome outcome) {
        return new QuizRewardExecutionClaim(outcome, null, null, null);
    }

    /**
     * 실행권 발급 결과. CLAIMED 외에는 아무것도 바꾸지 않았다. 실행권 발급과 Redis 재실행 허가는 다른 판단이라,
     * CLAIMED만 Redis를 실행할 수 있다.
     */
    public enum Outcome {
        /** 이번 호출이 실행권을 얻고 요청을 EXECUTING으로 바꿨다. */
        CLAIMED,
        /** 다른 실행권이 실행 중이다(EXECUTING). */
        ALREADY_EXECUTING,
        /** Redis가 이미 받아들였다(ACCEPTED). 원장 반영을 기다린다. */
        ALREADY_ACCEPTED,
        /** 이전 실행 결과를 확인할 수 없다(UNKNOWN). 증거 확인 전에는 다시 실행하지 않는다. */
        UNKNOWN,
        /** 원장에 반영됐다(APPLIED). 최종 상태다. */
        ALREADY_APPLIED,
        /** 재시도 가능한 실패 상태지만 이전 실행이 무반영이라는 기록이 없어 다시 실행할 수 없다. */
        NOT_RETRYABLE,
        /** 요청이 없거나, 입력이 저장된 요청과 다르거나, 저장된 상태끼리 맞지 않는다. */
        MISMATCH
    }
}
