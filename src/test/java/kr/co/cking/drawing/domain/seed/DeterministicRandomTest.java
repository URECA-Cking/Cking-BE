package kr.co.cking.drawing.domain.seed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;

class DeterministicRandomTest {

    private static final DrawingSeed FIRST_SEED = DrawingSeed.from("00".repeat(DrawingSeed.BYTE_LENGTH));
    private static final DrawingSeed SECOND_SEED = DrawingSeed.from("01".repeat(DrawingSeed.BYTE_LENGTH));

    @Test
    void 동일_Seed는_항상_동일한_난수열을_생성한다() {
        long[] first = sequence(FIRST_SEED);
        long[] second = sequence(FIRST_SEED);

        assertThat(first).containsExactly(second);
    }

    @Test
    void 다른_Seed는_다른_난수열을_생성한다() {
        assertThat(sequence(FIRST_SEED)).isNotEqualTo(sequence(SECOND_SEED));
    }

    @Test
    void 고정_Seed의_난수열을_알고리즘_회귀값으로_검증한다() {
        DeterministicRandom random = new DeterministicRandom(FIRST_SEED);

        assertThat(LongStream.range(0, 4).map(ignored -> random.nextLong()).toArray())
                .containsExactly(
                        4331819624843471649L,
                        2997818651030172446L,
                        -764401513470899079L,
                        8354157296877167359L
                );
    }

    @Test
    void 범위_난수는_요청한_상한_미만으로_생성한다() {
        DeterministicRandom random = new DeterministicRandom(FIRST_SEED);

        long[] values = LongStream.range(0, 1000)
                .map(ignored -> random.nextLong(7))
                .toArray();

        assertThat(LongStream.of(values).allMatch(value -> value >= 0 && value < 7)).isTrue();
    }

    @Test
    void 범위_상한이_양수가_아니면_거부한다() {
        DeterministicRandom random = new DeterministicRandom(FIRST_SEED);

        assertThatThrownBy(() -> random.nextLong(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bound");
    }

    private long[] sequence(DrawingSeed seed) {
        DeterministicRandom random = new DeterministicRandom(seed);
        return LongStream.range(0, 10)
                .map(ignored -> random.nextLong())
                .toArray();
    }
}
