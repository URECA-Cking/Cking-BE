package kr.co.cking.ticket.application;

import kr.co.cking.ticket.domain.QuizRewardRequestStatus;

/**
 * Quiz 보상 지급 요청 접수 결과(이슈 #533). 접수는 Ticket 원장 반영 완료가 아니다.
 *
 * <p>충돌 결과는 기존 요청을 바꾸지 않았다는 뜻이며 요청 정보를 담지 않는다.
 *
 * @param quizRewardRequestId 접수된 요청 ID. 충돌이면 null
 * @param status              접수된 요청의 현재 상태. 충돌이면 null
 */
public record QuizRewardAdmission(Outcome outcome, Long quizRewardRequestId, QuizRewardRequestStatus status) {

    /** 접수·재요청은 요청 ID와 상태가 모두 있어야 하고, 충돌은 둘 다 없어야 한다. 하나만 있으면 거부한다. */
    public QuizRewardAdmission {
        if (outcome == null) {
            throw new IllegalArgumentException("접수 결과가 필요합니다.");
        }
        boolean admitted = outcome == Outcome.RECEIVED || outcome == Outcome.REPLAYED;
        boolean valid = admitted
                ? quizRewardRequestId != null && status != null
                : quizRewardRequestId == null && status == null;
        if (!valid) {
            throw new IllegalArgumentException("접수 결과와 요청 정보가 맞지 않습니다. outcome=" + outcome);
        }
    }

    public static QuizRewardAdmission received(Long quizRewardRequestId, QuizRewardRequestStatus status) {
        return new QuizRewardAdmission(Outcome.RECEIVED, quizRewardRequestId, status);
    }

    public static QuizRewardAdmission replayed(Long quizRewardRequestId, QuizRewardRequestStatus status) {
        return new QuizRewardAdmission(Outcome.REPLAYED, quizRewardRequestId, status);
    }

    public static QuizRewardAdmission conflict(Outcome outcome) {
        return new QuizRewardAdmission(outcome, null, null);
    }

    public enum Outcome {
        /** 이번 호출이 새로 접수했다. */
        RECEIVED,
        /** 같은 rewardRequestId·같은 입력으로 이미 접수된 요청이다. 새로 접수하지 않았다. */
        REPLAYED,
        /** 같은 rewardRequestId가 다른 입력이나 다른 적립 원인(Mission 등)으로 이미 쓰였다. */
        REQUEST_ID_CONFLICT,
        /** 같은 attempt가 다른 rewardRequestId로 이미 접수됐다. 기존 요청은 바꾸지 않았다. */
        ATTEMPT_CONFLICT
    }
}
