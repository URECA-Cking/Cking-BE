package kr.co.cking.quiz.application;
import kr.co.cking.quiz.application.dto.*;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class FanQuizAttemptServiceTest {
    private final QuizAttemptStore store = mock(QuizAttemptStore.class);
    private final FanQuizAttemptService service = new FanQuizAttemptService(store);
    private final QuizAttemptCommand command = new QuizAttemptCommand(7L,1L,2L,UUID.randomUUID(),100L,List.of(new QuizAttemptCommand.Answer(0,0)));
    @Test void 기존_응시는_다시_저장하지_않는다() {
        var result=mock(QuizAttemptResult.class); when(store.findExisting(command)).thenReturn(Optional.of(result));
        assertThat(service.submit(command)).isSameAs(result); verify(store,never()).submit(any());
    }
    @Test void UNIQUE_실패후_새_Store_호출로_재조회한다() {
        var result=mock(QuizAttemptResult.class); when(store.findExisting(command)).thenReturn(Optional.empty(),Optional.of(result));
        when(store.submit(command)).thenThrow(new DataIntegrityViolationException("unique"));
        assertThat(service.submit(command)).isSameAs(result);
        var order=inOrder(store); order.verify(store).findExisting(command);order.verify(store).submit(command);order.verify(store).findExisting(command);
    }
    @Test void 재조회에도_없으면_원인을_숨기지_않는다() {
        var failure=new DataIntegrityViolationException("unique");when(store.findExisting(command)).thenReturn(Optional.empty());when(store.submit(command)).thenThrow(failure);
        assertThatThrownBy(()->service.submit(command)).isSameAs(failure);
    }
    @Test void 외부_트랜잭션을_거부한다() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {assertThatThrownBy(()->service.submit(command)).isInstanceOf(IllegalStateException.class);verifyNoInteractions(store);}
        finally {TransactionSynchronizationManager.setActualTransactionActive(false);}
    }
}
