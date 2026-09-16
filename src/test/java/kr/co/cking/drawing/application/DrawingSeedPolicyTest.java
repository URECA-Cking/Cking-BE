package kr.co.cking.drawing.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import kr.co.cking.drawing.domain.seed.DrawingSeed;
import kr.co.cking.drawing.domain.seed.DrawingSeedGenerator;
import org.junit.jupiter.api.Test;

class DrawingSeedPolicyTest {

    private static final DrawingSeed FIRST_SEED = DrawingSeed.from("00".repeat(DrawingSeed.BYTE_LENGTH));
    private static final DrawingSeed SECOND_SEED = DrawingSeed.from("01".repeat(DrawingSeed.BYTE_LENGTH));

    @Test
    void INITIAL_Drawing에는_신규_Seed를_사용한다() {
        DrawingSeedPolicy policy = new DrawingSeedPolicy(() -> FIRST_SEED);

        assertThat(policy.createForInitial()).isEqualTo(FIRST_SEED);
    }

    @Test
    void Drawing_Retry에서는_저장된_Seed를_재사용한다() {
        DrawingSeedPolicy policy = new DrawingSeedPolicy(() -> SECOND_SEED);

        DrawingSeed reused = policy.reuseForRetry(FIRST_SEED.value());

        assertThat(reused).isEqualTo(FIRST_SEED);
    }

    @Test
    void Drawing_Retry의_저장_Seed가_비정상_형식이면_거부한다() {
        DrawingSeedPolicy policy = new DrawingSeedPolicy(() -> SECOND_SEED);

        assertThatThrownBy(() -> policy.reuseForRetry(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> policy.reuseForRetry("invalid"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void REDRAW_Drawing에는_이전과_다른_신규_Seed를_사용한다() {
        DrawingSeedGenerator generator = sequenceGenerator(FIRST_SEED, SECOND_SEED);
        DrawingSeedPolicy policy = new DrawingSeedPolicy(generator);

        DrawingSeed redrawSeed = policy.createForRedraw(FIRST_SEED);

        assertThat(redrawSeed).isEqualTo(SECOND_SEED);
    }

    @Test
    void REDRAW_Drawing의_이전_Seed가_null이면_거부한다() {
        DrawingSeedPolicy policy = new DrawingSeedPolicy(() -> SECOND_SEED);

        assertThatThrownBy(() -> policy.createForRedraw(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("이전 Drawing");
    }

    @Test
    void Seed_생성기가_null을_반환하면_거부한다() {
        DrawingSeedPolicy policy = new DrawingSeedPolicy(() -> null);

        assertThatThrownBy(policy::createForInitial)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("null");
    }

    private DrawingSeedGenerator sequenceGenerator(DrawingSeed... seeds) {
        Deque<DrawingSeed> sequence = new ArrayDeque<>();
        sequence.addAll(List.of(seeds));
        return sequence::removeFirst;
    }
}
