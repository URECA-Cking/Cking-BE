package kr.co.cking.drawing.domain.seed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class DrawingSeedTest {

    @Test
    void 정규_형식의_Seed를_생성한다() {
        String value = "01".repeat(DrawingSeed.BYTE_LENGTH);

        DrawingSeed seed = DrawingSeed.from(value);

        assertThat(seed.value()).isEqualTo(value);
        assertThat(seed.bytes()).containsOnly((byte) 1);
    }

    @Test
    void Seed가_null이면_거부한다() {
        assertThatThrownBy(() -> DrawingSeed.from(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Seed");
    }

    @Test
    void Seed가_공백이거나_길이가_다르면_거부한다() {
        assertThatThrownBy(() -> DrawingSeed.from(" "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DrawingSeed.from("01"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void Seed에_대문자나_16진수가_아닌_문자가_있으면_거부한다() {
        assertThatThrownBy(() -> DrawingSeed.from("AB".repeat(DrawingSeed.BYTE_LENGTH)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DrawingSeed.from("gg".repeat(DrawingSeed.BYTE_LENGTH)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
