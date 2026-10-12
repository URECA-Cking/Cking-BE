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
    void 결과를_기록하면_FINISHED가_되고_다시_기록할_수_없다() {
        TicketQuizRewardExecution execution = TicketQuizRewardExecution.start(1L, 1, TOKEN, NOW);
        Instant finishedAt = NOW.plusSeconds(1);

        execution.finish("ACCEPTED", "GUARD_NOT_COMPLETED", "1-0", false, finishedAt);

        assertThat(execution.getStatus()).isEqualTo(QuizRewardExecutionStatus.FINISHED);
        assertThat(execution.isStarted()).isFalse();
        assertThat(execution.getOutcome()).isEqualTo("ACCEPTED");
        assertThat(execution.getReason()).isEqualTo("GUARD_NOT_COMPLETED");
        assertThat(execution.getStreamId()).isEqualTo("1-0");
        assertThat(execution.isNotExecutedConfirmed()).isFalse();
        assertThat(execution.getFinishedAt()).isEqualTo(finishedAt);
        assertThatThrownBy(() -> execution.finish("UNKNOWN", null, null, false, finishedAt))
                .isInstanceOf(IllegalStateException.class);
        assertThat(execution.getOutcome()).isEqualTo("ACCEPTED");
    }

    @Test
    void 결과_분류와_종료_시각이_있어야_기록한다() {
        TicketQuizRewardExecution execution = TicketQuizRewardExecution.start(1L, 1, TOKEN, NOW);

        assertThatThrownBy(() -> execution.finish(null, null, null, false, NOW))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> execution.finish("UNKNOWN", null, null, false, null))
                .isInstanceOf(NullPointerException.class);
        assertThat(execution.isStarted()).isTrue();
        assertThat(execution.getOutcome()).isNull();
    }

    @Test
    void 상태는_V63_CHECK와_같은_2개다() {
        assertThat(QuizRewardExecutionStatus.values()).extracting(Enum::name).containsExactly("STARTED", "FINISHED");
    }
}
