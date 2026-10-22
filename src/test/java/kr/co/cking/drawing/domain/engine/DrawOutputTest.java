package kr.co.cking.drawing.domain.engine;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class DrawOutputTest {

    @Test
    void 동일한_memberId의_당첨자가_중복되면_결과를_생성하지_않는다() {
        List<DrawWinner> winners = List.of(
                new DrawWinner(1L, 1, 3L),
                new DrawWinner(1L, 2, 3L)
        );

        assertThatThrownBy(() -> output(winners))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("memberId");
    }

    @Test
    void 동일한_rank의_당첨자가_중복되면_결과를_생성하지_않는다() {
        List<DrawWinner> winners = List.of(
                new DrawWinner(1L, 1, 3L),
                new DrawWinner(2L, 1, 5L)
        );

        assertThatThrownBy(() -> output(winners))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("rank");
    }

    @Test
    void null_당첨자가_포함되면_결과를_생성하지_않는다() {
        List<DrawWinner> winners = new ArrayList<>();
        winners.add(null);

        assertThatThrownBy(() -> output(winners))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("null");
    }

    private DrawOutput output(List<DrawWinner> winners) {
        return new DrawOutput(DrawingAlgorithmVersion.WEIGHTED_V1, winners);
    }
}
