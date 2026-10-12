package kr.co.cking.quiz.application;

import kr.co.cking.quiz.application.dto.QuizAttemptCommand;
import kr.co.cking.quiz.application.dto.QuizAttemptResult;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 멱등 제출 조정자. 실패한 저장 트랜잭션이 롤백된 뒤 별도 조회 트랜잭션에서 판정한다. 저장이 커밋된 결과만 Ticket 보상에
 * 전달하고(이슈 #543), 응답에는 응답 시점의 지급 상태를 덧붙인다. 전달 실패는 응시 결과에 영향을 주지 않는다.
 */
@Service @RequiredArgsConstructor
public class FanQuizAttemptService {
    private final QuizAttemptStore store;
    private final QuizRewardDelivery rewardDelivery;
    public QuizAttemptResult submit(QuizAttemptCommand command) {
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Quiz 응시 제출은 트랜잭션 밖에서 호출해야 합니다.");
        QuizAttemptResult result = saveOrReplay(command);
        rewardDelivery.deliver(result, command.memberId());
        return result.withPaymentStatus(rewardDelivery.paymentStatusOf(result));
    }
    public QuizAttemptResult findMine(Long memberId, Long creatorId, Long quizId) {
        QuizAttemptResult result = store.findMine(memberId, creatorId, quizId);
        return result.withPaymentStatus(rewardDelivery.paymentStatusOf(result));
    }
    private QuizAttemptResult saveOrReplay(QuizAttemptCommand command) {
        var existing = store.findExisting(command);
        if (existing.isPresent()) return existing.get();
        try { return store.submit(command); }
        catch (DataIntegrityViolationException | ConcurrencyFailureException e) {
            return store.findExisting(command).orElseThrow(() -> e);
        }
    }
}
