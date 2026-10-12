package kr.co.cking.ticket.application;

import org.springframework.dao.ConcurrencyFailureException;

/**
 * 동시 접수와의 잠금 경쟁(deadlock·잠금 대기 시간 초과)으로 접수 Transaction이 롤백됐고, 새 Transaction에서 다시 봐도
 * 판정할 기존 요청이 아직 없다(이슈 #533). 앞선 접수가 커밋되기 전일 수 있으므로 호출자는 같은 rewardRequestId로 다시
 * 요청한다. 새 rewardRequestId를 만들지 않는다.
 */
public class QuizRewardAdmissionRetryableException extends IllegalStateException {

    public QuizRewardAdmissionRetryableException(String message, ConcurrencyFailureException cause) {
        super(message, cause);
    }
}
