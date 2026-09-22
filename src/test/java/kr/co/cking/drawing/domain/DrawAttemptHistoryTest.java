package kr.co.cking.drawing.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DrawAttemptHistoryTest {

    private static final Instant STARTED_AT = Instant.parse("2026-09-22T00:00:00Z");

    @Test
    void 시작한_Attempt를_성공으로_종결한다() {
        DrawAttemptHistory history = DrawAttemptHistory.started(1L, 2, 3L, STARTED_AT);

        history.succeed(STARTED_AT.plusSeconds(1));

        assertThat(history.getStatus()).isEqualTo(DrawAttemptStatus.SUCCEEDED);
        assertThat(history.getFinishedAt()).isEqualTo(STARTED_AT.plusSeconds(1));
    }

    @ParameterizedTest
    @ValueSource(strings = {"NON_RETRYABLE_FAILURE", "SNAPSHOT_HASH_MISMATCH", "INSUFFICIENT_CANDIDATES"})
    void 확정적인_비재시도_실패코드는_재시도할_수_없다(String failureCode) {
        DrawAttemptHistory history = DrawAttemptHistory.started(1L, 1, 3L, STARTED_AT);
        history.fail(DrawingFailureStage.INPUT_VERIFICATION, failureCode, "불일치",
                STARTED_AT.plusSeconds(1));

        assertThat(history.isRetryable()).isFalse();
    }

    @Test
    void 입력_검증_단계의_일시적인_시스템_오류는_재시도할_수_있다() {
        DrawAttemptHistory history = DrawAttemptHistory.started(1L, 1, 3L, STARTED_AT);
        history.fail(DrawingFailureStage.INPUT_VERIFICATION, "SYSTEM_ERROR", "DB timeout",
                STARTED_AT.plusSeconds(1));

        assertThat(history.isRetryable()).isTrue();
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
