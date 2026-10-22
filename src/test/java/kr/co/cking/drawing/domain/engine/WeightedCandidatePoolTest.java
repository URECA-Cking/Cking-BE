package kr.co.cking.drawing.domain.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import kr.co.cking.snapshot.domain.CandidateValue;
import org.junit.jupiter.api.Test;

class WeightedCandidatePoolTest {

    @Test
    void 누적_가중치_구간에_해당하는_후보를_선택한다() {
        assertThat(pool().selectAndRemove(0L).memberId()).isEqualTo(1L);
        assertThat(pool().selectAndRemove(1L).memberId()).isEqualTo(1L);
        assertThat(pool().selectAndRemove(2L).memberId()).isEqualTo(2L);
        assertThat(pool().selectAndRemove(6L).memberId()).isEqualTo(2L);
        assertThat(pool().selectAndRemove(7L).memberId()).isEqualTo(3L);
        assertThat(pool().selectAndRemove(14L).memberId()).isEqualTo(3L);
        assertThat(pool().selectAndRemove(15L).memberId()).isEqualTo(4L);
        assertThat(pool().selectAndRemove(27L).memberId()).isEqualTo(4L);
    }

    @Test
    void 선택한_후보의_가중치를_제거하고_다음_추첨에서_제외한다() {
        WeightedCandidatePool pool = pool();

        CandidateValue firstWinner = pool.selectAndRemove(6L);
        CandidateValue secondWinner = pool.selectAndRemove(6L);

        assertThat(firstWinner.memberId()).isEqualTo(2L);
        assertThat(secondWinner.memberId()).isEqualTo(3L);
        assertThat(pool.totalWeight()).isEqualTo(15L);
    }

    @Test
    void 가중치_합계가_long_범위를_초과하면_후보군을_생성하지_않는다() {
        List<CandidateValue> candidates = List.of(
                new CandidateValue(1L, Long.MAX_VALUE),
                new CandidateValue(2L, 1L)
        );

        assertThatThrownBy(() -> new WeightedCandidatePool(candidates))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("long 범위");
    }

    @Test
    void 현재_가중치_합계_밖의_기준값은_거부한다() {
        WeightedCandidatePool pool = pool();

        assertThatThrownBy(() -> pool.selectAndRemove(28L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("가중치 합계 범위");
    }

    private WeightedCandidatePool pool() {
        return new WeightedCandidatePool(List.of(
                new CandidateValue(1L, 2L),
                new CandidateValue(2L, 5L),
                new CandidateValue(3L, 8L),
                new CandidateValue(4L, 13L)
        ));
    }
}
