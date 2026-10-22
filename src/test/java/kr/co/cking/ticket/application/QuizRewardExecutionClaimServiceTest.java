package kr.co.cking.ticket.application;

import kr.co.cking.ticket.application.dto.QuizRedisEarnOutcome;
import kr.co.cking.ticket.application.dto.QuizRewardCommand;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Arrays;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class QuizRewardExecutionClaimServiceTest {

    private final QuizRewardExecutionStore store = mock(QuizRewardExecutionStore.class);
    private final QuizRewardExecutionClaimService service = new QuizRewardExecutionClaimService(store);
    private final QuizRewardCommand command = new QuizRewardCommand(100L, 7L, 10L, 3, UUID.randomUUID());

    @AfterEach
    void clearTransaction() {
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @Test
    void 발급_Transaction의_결과를_그대로_돌려준다() {
        QuizRewardExecutionClaim claimed = QuizRewardExecutionClaim.claimed(1L, UUID.randomUUID().toString(), 1);
        given(store.claim(command)).willReturn(claimed);

        assertThat(service.claim(command)).isEqualTo(claimed);
    }

    @Test
    void 이미_열린_Transaction_안에서는_발급하지_않는다() {
        TransactionSynchronizationManager.setActualTransactionActive(true);

        assertThatThrownBy(() -> service.claim(command)).isInstanceOf(IllegalStateException.class);
        verify(store, never()).claim(any());
    }

    @Test
    void 발급_Transaction이_실패하면_예외를_그대로_올린다() {
        given(store.claim(command)).willThrow(new IllegalStateException("rollback"));

        assertThatThrownBy(() -> service.claim(command)).isInstanceOf(IllegalStateException.class)
                .hasMessage("rollback");
    }

    @Test
    void 실행권은_CLAIMED에만_있고_다른_결과는_실행권을_담지_않는다() {
        for (QuizRewardExecutionClaim.Outcome outcome : QuizRewardExecutionClaim.Outcome.values()) {
            if (outcome == QuizRewardExecutionClaim.Outcome.CLAIMED) {
                assertThatThrownBy(() -> QuizRewardExecutionClaim.rejected(outcome))
                        .isInstanceOf(IllegalArgumentException.class);
                continue;
            }
            QuizRewardExecutionClaim rejected = QuizRewardExecutionClaim.rejected(outcome);
            assertThat(Arrays.asList(rejected.quizRewardRequestId(), rejected.executionToken(),
                    rejected.executionSeq())).containsOnlyNulls();
            assertThatThrownBy(() -> new QuizRewardExecutionClaim(outcome, 1L, "token", 1))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> QuizRewardExecutionClaim.claimed(1L, null, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> QuizRewardExecutionClaim.claimed(1L, "token", 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void UNKNOWN_실행_결과는_재시도_가능으로_분류되지_않는다() {
        assertThat(QuizRedisEarnOutcome.UNKNOWN.retryable()).isFalse();
        assertThat(QuizRedisEarnOutcome.UNKNOWN.confirmedNotExecuted()).isFalse();
    }
}
