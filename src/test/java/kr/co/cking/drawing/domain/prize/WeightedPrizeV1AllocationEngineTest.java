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

class WeightedPrizeV1AllocationEngineTest {
    private final PrizeAllocationEngine engine = new WeightedPrizeV1AllocationEngine();
    private final DrawingSeed seed = DrawingSeed.from("12".repeat(32));

    @Test
    void 동일한_상품_Snapshot과_Seed로_Retry하면_동일한_상품을_배정한다() {
        PrizeAllocationInput input = input(seed, prizes());

        PrizeAllocationOutput first = engine.allocate(input);
        PrizeAllocationOutput retry = engine.allocate(input);

        assertThat(retry).isEqualTo(first);
        assertThat(first.allocations()).hasSize(4);
    }

    @Test
    void 재고가_소진된_상품은_이후_배정에서_제외한다() {
        PrizeAllocationOutput output = engine.allocate(input(seed, List.of(
                new PrizeValue(1L, "FIRST", "1등", 1, 5, 1),
                new PrizeValue(2L, "SECOND", "2등", 2, 95, 3)
        )));

        Map<String, Long> counts = output.allocations().stream().collect(Collectors.groupingBy(
                allocation -> allocation.prize().prizeKey(), Collectors.counting()));
        assertThat(counts.getOrDefault("FIRST", 0L)).isLessThanOrEqualTo(1L);
        assertThat(counts.getOrDefault("SECOND", 0L)).isLessThanOrEqualTo(3L);
        assertThat(counts.values()).hasSizeBetween(1, 2);
        assertThat(counts.values().stream().mapToLong(Long::longValue).sum()).isEqualTo(4);
    }

    @Test
    void 상품_수량이_당첨자보다_적으면_배정_전에_거부한다() {
        assertThatThrownBy(() -> input(seed, List.of(
                new PrizeValue(1L, "ONLY", "한정", 1, 1, 3))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("상품 수량");
    }

    @Test
    void 상품_가중치_합계_overflow는_배정_전에_거부한다() {
        assertThatThrownBy(() -> input(seed, List.of(
                new PrizeValue(1L, "A", "A", 1, Long.MAX_VALUE, 2),
                new PrizeValue(2L, "B", "B", 2, 1, 2))))
                .isInstanceOf(ArithmeticException.class);
    }

    private PrizeAllocationInput input(DrawingSeed drawingSeed, List<PrizeValue> prizes) {
        return new PrizeAllocationInput(drawingSeed, "PRIZE_WEIGHTED_V1", List.of(
                new DrawWinner(1L, 1, 10), new DrawWinner(2L, 2, 10),
                new DrawWinner(3L, 3, 10), new DrawWinner(4L, 4, 10)), prizes);
    }

    private List<PrizeValue> prizes() {
        return List.of(new PrizeValue(1L, "FIRST", "1등", 1, 5, 1),
                new PrizeValue(2L, "SECOND", "2등", 2, 95, 3));
    }
}
