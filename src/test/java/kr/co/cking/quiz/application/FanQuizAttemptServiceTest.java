package kr.co.cking.quiz.application;
import kr.co.cking.quiz.application.dto.*;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class FanQuizAttemptServiceTest {
    private final QuizAttemptStore store = mock(QuizAttemptStore.class);
    private final QuizRewardDelivery delivery = mock(QuizRewardDelivery.class);
    private final FanQuizAttemptService service = new FanQuizAttemptService(store, delivery);
    private final QuizAttemptCommand command = new QuizAttemptCommand(7L,1L,2L,UUID.randomUUID(),100L,List.of(new QuizAttemptCommand.Answer(0,0)));
    private final QuizAttemptResult result = new QuizAttemptResult(5L, command.requestId(), 1L, 2L, 100L, "제목", "video", 1, 1,
            Instant.parse("2026-10-12T00:00:00Z"), "a".repeat(64), "b".repeat(64), List.of(),
            new QuizAttemptResult.Reward(1, UUID.randomUUID(), "NOT_INTEGRATED"));
    @Test void 기존_응시는_다시_저장하지_않는다() {
        when(store.findExisting(command)).thenReturn(Optional.of(result));
        assertThat(service.submit(command)).isEqualTo(result.withPaymentStatus(null)); verify(store,never()).submit(any());
    }
    @Test void UNIQUE_실패후_새_Store_호출로_재조회한다() {
        when(store.findExisting(command)).thenReturn(Optional.empty(),Optional.of(result));
        when(store.submit(command)).thenThrow(new DataIntegrityViolationException("unique"));
        assertThat(service.submit(command)).isEqualTo(result.withPaymentStatus(null));
        var order=inOrder(store); order.verify(store).findExisting(command);order.verify(store).submit(command);order.verify(store).findExisting(command);
    }
    @Test void 재조회에도_없으면_원인을_숨기지_않는다() {
        var failure=new DataIntegrityViolationException("unique");when(store.findExisting(command)).thenReturn(Optional.empty());when(store.submit(command)).thenThrow(failure);
        assertThatThrownBy(()->service.submit(command)).isSameAs(failure);
        verifyNoInteractions(delivery);
    }
    @Test void 외부_트랜잭션을_거부한다() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {assertThatThrownBy(()->service.submit(command)).isInstanceOf(IllegalStateException.class);verifyNoInteractions(store, delivery);}
        finally {TransactionSynchronizationManager.setActualTransactionActive(false);}
    }
    @Test void 저장이_커밋된_결과를_전달한_뒤_지급_상태를_덧붙인다() {
        when(store.findExisting(command)).thenReturn(Optional.empty());
        when(store.submit(command)).thenReturn(result);
        when(delivery.paymentStatusOf(result)).thenReturn(QuizRewardPaymentStatus.PAID);
        var response = service.submit(command);
        var order=inOrder(store, delivery); order.verify(store).submit(command); order.verify(delivery).deliver(result, 7L); order.verify(delivery).paymentStatusOf(result);
        assertThat(response.reward().paymentStatus()).isEqualTo(QuizRewardPaymentStatus.PAID);
        // 저장값과 hash는 그대로다.
        assertThat(response.reward().status()).isEqualTo("NOT_INTEGRATED");
        assertThat(response.snapshotSha256()).isEqualTo(result.snapshotSha256());
        assertThat(response.reward().requestId()).isEqualTo(result.reward().requestId());
    }
    @Test void 같은_요청의_재전송도_같은_결과로_다시_전달한다() {
        when(store.findExisting(command)).thenReturn(Optional.of(result));
        service.submit(command); service.submit(command);
        verify(delivery, times(2)).deliver(result, 7L);
    }
    @Test void 본인_결과_조회에도_지급_상태를_덧붙이고_전달하지_않는다() {
        when(store.findMine(7L,1L,2L)).thenReturn(result);
        when(delivery.paymentStatusOf(result)).thenReturn(QuizRewardPaymentStatus.UNAVAILABLE);
        assertThat(service.findMine(7L,1L,2L).reward().paymentStatus()).isEqualTo(QuizRewardPaymentStatus.UNAVAILABLE);
        verify(delivery, never()).deliver(any(), any());
    }
}
