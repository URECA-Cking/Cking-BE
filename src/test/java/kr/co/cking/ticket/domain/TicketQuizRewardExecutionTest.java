package kr.co.cking.ticket.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TicketQuizRewardExecutionTest {

    private static final Instant NOW = Instant.parse("2026-10-12T06:00:00Z");
    private static final String TOKEN = "550e8400-e29b-41d4-a716-446655440000";

    @Test
    void 결과_없이_STARTED로_시작한다() {
        TicketQuizRewardExecution execution = TicketQuizRewardExecution.start(1L, 2, TOKEN, NOW);

        assertThat(execution.getQuizRewardRequestId()).isEqualTo(1L);
        assertThat(execution.getExecutionSeq()).isEqualTo(2);
        assertThat(execution.getExecutionToken()).isEqualTo(TOKEN);
        assertThat(execution.getStatus()).isEqualTo(QuizRewardExecutionStatus.STARTED);
        assertThat(execution.isStarted()).isTrue();
        assertThat(execution.getStartedAt()).isEqualTo(NOW);
        assertThat(execution.getOutcome()).isNull();
        assertThat(execution.getReason()).isNull();
        assertThat(execution.getStreamId()).isNull();
        assertThat(execution.isNotExecutedConfirmed()).isFalse();
        assertThat(execution.getFinishedAt()).isNull();
    }

    @Test
    void 순번은_1_이상이고_필수_값이_있어야_한다() {
        assertThatThrownBy(() -> TicketQuizRewardExecution.start(1L, 0, TOKEN, NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TicketQuizRewardExecution.start(null, 1, TOKEN, NOW))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> TicketQuizRewardExecution.start(1L, 1, null, NOW))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> TicketQuizRewardExecution.start(1L, 1, TOKEN, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void 상태는_V62_CHECK와_같은_2개다() {
        assertThat(QuizRewardExecutionStatus.values()).extracting(Enum::name).containsExactly("STARTED", "FINISHED");
    }
}
