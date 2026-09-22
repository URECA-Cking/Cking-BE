package kr.co.cking.drawing.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class DrawAttemptHistoryTest {

    private static final Instant STARTED_AT = Instant.parse("2026-09-22T00:00:00Z");

    @Test
    void 시작한_Attempt를_성공으로_종결한다() {
        DrawAttemptHistory history = DrawAttemptHistory.started(1L, 2, 3L, STARTED_AT);

        history.succeed(STARTED_AT.plusSeconds(1));

        assertThat(history.getStatus()).isEqualTo(DrawAttemptStatus.SUCCEEDED);
        assertThat(history.getFinishedAt()).isEqualTo(STARTED_AT.plusSeconds(1));
    }

    @Test
    void 입력_검증_실패는_재시도할_수_없다() {
        DrawAttemptHistory history = DrawAttemptHistory.started(1L, 1, 3L, STARTED_AT);
        history.fail(DrawingFailureStage.INPUT_VERIFICATION, "SNAPSHOT_HASH_MISMATCH", "불일치",
                STARTED_AT.plusSeconds(1));

        assertThat(history.isRetryable()).isFalse();
    }

    @Test
    void 서버_중단_실패는_복구할_수_있다() {
        DrawAttemptHistory history = DrawAttemptHistory.started(1L, 1, null, STARTED_AT);

        history.interrupt(STARTED_AT.plusSeconds(10));

        assertThat(history.getFailureStage()).isEqualTo(DrawingFailureStage.SERVER_INTERRUPTED);
        assertThat(history.isRetryable()).isTrue();
    }

    @Test
    void 종료된_Attempt는_다시_종결할_수_없다() {
        DrawAttemptHistory history = DrawAttemptHistory.started(1L, 1, 3L, STARTED_AT);
        history.succeed(STARTED_AT.plusSeconds(1));

        assertThatThrownBy(() -> history.interrupt(STARTED_AT.plusSeconds(2)))
                .isInstanceOf(IllegalStateException.class);
    }
}
