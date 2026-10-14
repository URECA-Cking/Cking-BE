package kr.co.cking.quiz.application;
import kr.co.cking.quiz.application.dto.QuizCompositionCommand;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Store 프록시의 실패 트랜잭션 종료 후 새 조회 트랜잭션에서만 replay를 복구한다. */
@Service @RequiredArgsConstructor
public class CreatorQuizCompositionService {
    private final QuizCompositionStore store;
    public QuizCompositionStore.Admission compose(Long memberId, Long quizId, QuizCompositionCommand command) {
        if(TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Quiz 편집은 트랜잭션 밖에서 호출해야 합니다.");
        try { return store.compose(memberId,quizId,command); }
        catch(DataIntegrityViolationException | ConcurrencyFailureException e) {
            return store.findExisting(memberId,quizId,command).orElseThrow(()->e);
        }
    }
}
