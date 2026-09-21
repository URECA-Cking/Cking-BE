package kr.co.cking.drawing.domain.prize;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import kr.co.cking.drawing.domain.engine.DrawWinner;
import kr.co.cking.drawing.domain.seed.DrawingSeed;
import kr.co.cking.snapshot.domain.PrizeValue;
import org.junit.jupiter.api.Test;

class UniformPrizeV1AllocationEngineTest {
    private static final DrawingSeed SEED = DrawingSeed.from("34".repeat(32));
    private final PrizeAllocationEngine engine = new UniformPrizeV1AllocationEngine();

    @Test
    void 상품_가중치가_달라도_같은_Seed에서_같은_상품을_배정한다() {
        PrizeAllocationOutput first = engine.allocate(input(List.of(
                new PrizeValue(1L, "A", "A", 1, 1, 2),
                new PrizeValue(2L, "B", "B", 2, 999, 2)
        )));
        PrizeAllocationOutput changedWeights = engine.allocate(input(List.of(
                new PrizeValue(1L, "A", "A", 1, Long.MAX_VALUE, 2),
                new PrizeValue(2L, "B", "B", 2, Long.MAX_VALUE, 2)
        )));

        assertThat(changedWeights.allocations()).extracting(allocation -> allocation.prize().prizeKey())
                .containsExactlyElementsOf(first.allocations().stream()
                        .map(allocation -> allocation.prize().prizeKey()).toList());
        assertThat(first.algorithmVersion()).isEqualTo(PrizeAllocationAlgorithmVersion.PRIZE_UNIFORM_V1);
    }

    @Test
    void 재고가_소진된_상품은_이후_배정에서_제외한다() {
        PrizeAllocationOutput output = engine.allocate(input(List.of(
                new PrizeValue(1L, "LIMITED", "한정", 1, 1, 1),
                new PrizeValue(2L, "NORMAL", "일반", 2, 1, 3)
        )));

        Map<String, Long> counts = output.allocations().stream().collect(Collectors.groupingBy(
                allocation -> allocation.prize().prizeKey(), Collectors.counting()));
        assertThat(counts.getOrDefault("LIMITED", 0L)).isLessThanOrEqualTo(1L);
        assertThat(counts.getOrDefault("NORMAL", 0L)).isLessThanOrEqualTo(3L);
        assertThat(output.allocations()).hasSize(4);
    }

    @Test
    void PRIZE_WEIGHTED_V1_입력은_거부한다() {
        PrizeAllocationInput input = new PrizeAllocationInput(SEED, "PRIZE_WEIGHTED_V1", winners(), prizes());

        assertThatThrownBy(() -> engine.allocate(input))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("PRIZE_UNIFORM_V1");
    }

    private PrizeAllocationInput input(List<PrizeValue> prizes) {
        return new PrizeAllocationInput(SEED, "PRIZE_UNIFORM_V1", winners(), prizes);
    }

    private List<DrawWinner> winners() {
        return List.of(new DrawWinner(1L, 1, 10), new DrawWinner(2L, 2, 10),
                new DrawWinner(3L, 3, 10), new DrawWinner(4L, 4, 10));
    }

    private List<PrizeValue> prizes() {
        return List.of(new PrizeValue(1L, "A", "A", 1, 1, 2),
                new PrizeValue(2L, "B", "B", 2, 1, 2));
    }
}
