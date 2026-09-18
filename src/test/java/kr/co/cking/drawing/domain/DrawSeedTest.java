package kr.co.cking.drawing.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import kr.co.cking.drawing.domain.seed.DrawingSeed;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class DrawSeedTest {

    private static final DrawingSeed SEED = DrawingSeed.from("01".repeat(32));

    @Test
    void DrawingSeed를_32바이트로_보관하고_복원한다() {
        DrawSeed stored = DrawSeed.create(SEED);

        assertThat(stored.getSeedValue()).hasSize(DrawingSeed.BYTE_LENGTH);
        assertThat(stored.restore()).isEqualTo(SEED);
    }

    @Test
    void 반환된_바이트배열을_변경해도_저장값은_유지된다() {
        DrawSeed stored = DrawSeed.create(SEED);

        byte[] exposed = stored.getSeedValue();
        exposed[0] = 127;

        assertThat(stored.restore()).isEqualTo(SEED);
    }

    @Test
    void null_Seed는_저장할_수_없다() {
        assertThatThrownBy(() -> DrawSeed.create(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void DB의_비정상_길이_Seed는_복원할_수_없다() {
        DrawSeed stored = DrawSeed.create(SEED);
        ReflectionTestUtils.setField(stored, "seedValue", new byte[31]);

        assertThatThrownBy(stored::restore)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("올바르지 않습니다");
    }
}
