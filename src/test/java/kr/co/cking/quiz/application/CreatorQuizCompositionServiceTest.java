package kr.co.cking.quiz.application;
import kr.co.cking.quiz.application.dto.QuizCompositionCommand;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

class CreatorQuizCompositionServiceTest {
    @Test void 실패한_store_호출이_끝난_뒤에만_replay를_조회한다(){
        var store=mock(QuizCompositionStore.class);var service=new CreatorQuizCompositionService(store);
        var command=new QuizCompositionCommand(UUID.randomUUID(),1L,"a".repeat(64),List.of(new QuizCompositionCommand.Selection(1L,null)));
        var failure=new DataIntegrityViolationException("unique");when(store.compose(7L,1L,command)).thenThrow(failure);
        when(store.findExisting(7L,1L,command)).thenReturn(Optional.of(new QuizCompositionStore.Admission(2L,false)));
        assertThat(service.compose(7L,1L,command).created()).isFalse();
        var order=inOrder(store);order.verify(store).compose(7L,1L,command);order.verify(store).findExisting(7L,1L,command);
        when(store.findExisting(7L,1L,command)).thenReturn(Optional.empty());
        assertThatThrownBy(()->service.compose(7L,1L,command)).isSameAs(failure);
    }
    @Test void 외부_트랜잭션에서는_접수하지_않는다(){
        var store=mock(QuizCompositionStore.class);
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try{assertThatThrownBy(()->new CreatorQuizCompositionService(store).compose(7L,1L,null)).isInstanceOf(IllegalStateException.class);verifyNoInteractions(store);}
        finally{TransactionSynchronizationManager.setActualTransactionActive(false);}
    }
}
