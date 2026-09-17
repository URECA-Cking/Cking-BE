package kr.co.cking.drawing.domain.seed;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SecureDrawingSeedGeneratorTest {

    private final SecureDrawingSeedGenerator generator = new SecureDrawingSeedGenerator();

    @Test
    void 신규_Drawing용_Seed를_정규_형식으로_생성한다() {
        DrawingSeed seed = generator.generate();

        assertThat(seed.value())
                .hasSize(DrawingSeed.TEXT_LENGTH)
                .matches("[0-9a-f]+");
    }
}
