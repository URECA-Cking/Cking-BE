package kr.co.cking.drawing.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import kr.co.cking.drawing.application.DrawingVerificationResult;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class DrawingVerificationHistoryTest {

    @Test
    void 검증_이력은_재실행_Seed를_방어적으로_복사한다() {
        byte[] seed = new byte[32];
        seed[0] = 1;

        DrawingVerificationHistory history = DrawingVerificationHistory.record(
                10L, DrawingVerificationStatus.VERIFIED, seed, 2, 2,
                true, true, true, true, true,
                true, true, true, true,
                null, null, 1L, Instant.parse("2026-09-18T00:00:00Z")
        );

        seed[0] = 9;
        byte[] exposed = history.getReplaySeedValue();
        exposed[0] = 8;

        assertThat(history.getReplaySeedValue()[0]).isEqualTo((byte) 1);
        assertThat(history.getVerificationMode())
                .isEqualTo(DrawingVerificationMode.DETERMINISTIC_AND_CARDINALITY_REPLAY);
    }

    @Test
    void 재실행_Seed는_32바이트여야_한다() {
        assertThatThrownBy(() -> DrawingVerificationHistory.record(
                10L, DrawingVerificationStatus.VERIFIED, new byte[31], 2, 2,
                true, true, true, true, true,
                true, true, true, true,
                null, null, 1L, Instant.parse("2026-09-18T00:00:00Z")
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 마이그레이션_이전_이력의_기대_당첨자_수가_null이어도_조회할_수_있다() {
        DrawingVerificationHistory legacyHistory = new DrawingVerificationHistory();
        ReflectionTestUtils.setField(legacyHistory, "id", 1L);
        ReflectionTestUtils.setField(legacyHistory, "drawingId", 10L);
        ReflectionTestUtils.setField(legacyHistory, "status", DrawingVerificationStatus.VERIFIED);
        ReflectionTestUtils.setField(
                legacyHistory,
                "verificationMode",
                DrawingVerificationMode.LEGACY_INTEGRITY
        );

        DrawingVerificationResult result = DrawingVerificationResult.from(legacyHistory);

        assertThat(result.expectedWinnerCount()).isNull();
        assertThat(result.verificationMode()).isEqualTo(DrawingVerificationMode.LEGACY_INTEGRITY);
    }
}
