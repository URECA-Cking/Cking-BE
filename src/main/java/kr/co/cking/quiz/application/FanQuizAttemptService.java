package kr.co.cking.quiz.application;

import kr.co.cking.quiz.application.dto.QuizAttemptCommand;
import kr.co.cking.quiz.application.dto.QuizAttemptResult;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 멱등 제출 조정자. 실패한 저장 트랜잭션이 롤백된 뒤 별도 조회 트랜잭션에서 판정한다. */
@Service @RequiredArgsConstructor
public class FanQuizAttemptService {
    private final QuizAttemptStore store;
    public QuizAttemptResult submit(QuizAttemptCommand command) {
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Quiz 응시 제출은 트랜잭션 밖에서 호출해야 합니다.");
        var existing = store.findExisting(command);
        if (existing.isPresent()) return existing.get();
        try { return store.submit(command); }
        catch (DataIntegrityViolationException | ConcurrencyFailureException e) {
            return store.findExisting(command).orElseThrow(() -> e);
        }
    }
    public QuizAttemptResult findMine(Long memberId, Long creatorId, Long quizId) {
        return store.findMine(memberId, creatorId, quizId);
    }
}
