package kr.co.cking.drawing.domain.hash;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import kr.co.cking.drawing.domain.engine.DrawInput;
import kr.co.cking.drawing.domain.engine.DrawOutput;
import kr.co.cking.drawing.domain.engine.DrawWinner;
import kr.co.cking.drawing.domain.engine.DrawingAlgorithmVersion;
import kr.co.cking.drawing.domain.prize.AllocatedPrize;
import kr.co.cking.drawing.domain.prize.PrizeAllocationAlgorithmVersion;
import kr.co.cking.drawing.domain.prize.PrizeAllocationOutput;
import kr.co.cking.drawing.domain.seed.DrawingSeed;
import kr.co.cking.snapshot.domain.CandidateValue;
import kr.co.cking.snapshot.domain.PrizeValue;
import org.junit.jupiter.api.Test;

class PrizeDrawingHashV2Test {
    private final DrawInputV2HashGenerator inputGenerator = new DrawInputV2HashGenerator();
    private final DrawResultV2HashGenerator resultGenerator = new DrawResultV2HashGenerator();

    @Test
    void 상품_가중치나_수량이_변경되면_Input_Hash가_변경된다() {
        DrawInput input = input();
        List<PrizeValue> original = prizes(5, 1);

        assertThat(inputGenerator.generate(input, "PRIZE_WEIGHTED_V1", original).value())
                .isNotEqualTo(inputGenerator.generate(input, "PRIZE_WEIGHTED_V1", prizes(6, 1)).value())
                .isNotEqualTo(inputGenerator.generate(input, "PRIZE_WEIGHTED_V1", prizes(5, 2)).value());
    }

    @Test
    void 당첨자별_배정_상품이_변경되면_Result_Hash가_변경된다() {
        DrawOutput winners = new DrawOutput(DrawingAlgorithmVersion.WEIGHTED_V1,
                List.of(new DrawWinner(1L, 1, 3), new DrawWinner(2L, 2, 7)));
        PrizeValue first = prizes(5, 1).get(0);
        PrizeValue second = prizes(5, 1).get(1);
        PrizeAllocationOutput original = output(first, second);
        PrizeAllocationOutput swapped = output(second, first);

        assertThat(resultGenerator.generate("ab".repeat(32), winners, original).value())
                .isNotEqualTo(resultGenerator.generate("ab".repeat(32), winners, swapped).value());
    }

    private DrawInput input() {
        return new DrawInput(10L, 20L, "ab".repeat(32), DrawingSeed.from("01".repeat(32)),
                "WEIGHTED_V1", 2, List.of(new CandidateValue(1L, 3), new CandidateValue(2L, 7)), Set.of());
    }

    private List<PrizeValue> prizes(long firstWeight, int firstQuantity) {
        return List.of(new PrizeValue(1L, "FIRST", "1등", 1, firstWeight, firstQuantity),
                new PrizeValue(2L, "SECOND", "2등", 2, 95, 2));
    }

    private PrizeAllocationOutput output(PrizeValue first, PrizeValue second) {
        return new PrizeAllocationOutput(PrizeAllocationAlgorithmVersion.PRIZE_WEIGHTED_V1,
                List.of(new AllocatedPrize(1L, 1, first), new AllocatedPrize(2L, 2, second)));
    }
}
