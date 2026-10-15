package kr.co.cking.ticket.application;

import kr.co.cking.ticket.application.dto.QuizRewardCommand;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Quiz 보상 Redis 실행 전에 실행권을 발급한다(이슈 #533). 발급은 {@link QuizRewardExecutionStore#claim}의 독립
 * Transaction으로 끝나고, 이 메서드가 반환할 때 이미 커밋돼 있다. 그래서 호출자는 DB Transaction이나 행 잠금 없이 Redis를
 * 실행할 수 있다.
 *
 * <p>바깥 Transaction에 합류하면 Redis 실행 전에 커밋되지 않으므로, Transaction 밖에서만 호출할 수 있다. 잠금 경쟁
 * (deadlock·잠금 대기 초과)이나 DB 오류는 그대로 올라가며, 그때는 발급 Transaction이 롤백돼 아무것도 바뀌지 않는다.
 */
@Service
@RequiredArgsConstructor
public class QuizRewardExecutionClaimService {

    private final QuizRewardExecutionStore store;

    public QuizRewardExecutionClaim claim(QuizRewardCommand command) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Quiz 보상 실행권 발급은 Transaction 밖에서 호출해야 합니다.");
        }
        return store.claim(command);
    }
}
