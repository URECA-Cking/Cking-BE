package kr.co.cking.drawing.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import kr.co.cking.drawing.domain.engine.DrawInput;
import kr.co.cking.drawing.domain.engine.DrawOutput;
import kr.co.cking.drawing.domain.engine.DrawingAlgorithmVersion;
import kr.co.cking.drawing.domain.engine.ResolvingDrawingEngine;
import kr.co.cking.drawing.domain.engine.UniformV1DrawingEngine;
import kr.co.cking.drawing.domain.engine.WeightedV1DrawingEngine;
import kr.co.cking.drawing.domain.prize.PrizeAllocationAlgorithmVersion;
import kr.co.cking.drawing.domain.prize.PrizeAllocationInput;
import kr.co.cking.drawing.domain.prize.PrizeAllocationOutput;
import kr.co.cking.drawing.domain.prize.ResolvingPrizeAllocationEngine;
import kr.co.cking.drawing.domain.prize.UniformPrizeV1AllocationEngine;
import kr.co.cking.drawing.domain.prize.WeightedPrizeV1AllocationEngine;
import kr.co.cking.drawing.domain.seed.DrawingSeed;
import kr.co.cking.snapshot.domain.CandidateValue;
import kr.co.cking.snapshot.domain.PrizeValue;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class AlgorithmCombinationTest {
    private static final DrawingSeed SEED = DrawingSeed.from("45".repeat(32));
    private final ResolvingDrawingEngine drawingEngine = new ResolvingDrawingEngine(Map.of(
            DrawingAlgorithmVersion.UNIFORM_V1, new UniformV1DrawingEngine(),
            DrawingAlgorithmVersion.WEIGHTED_V1, new WeightedV1DrawingEngine()));
    private final ResolvingPrizeAllocationEngine prizeEngine = new ResolvingPrizeAllocationEngine(Map.of(
            PrizeAllocationAlgorithmVersion.PRIZE_UNIFORM_V1, new UniformPrizeV1AllocationEngine(),
            PrizeAllocationAlgorithmVersion.PRIZE_WEIGHTED_V1, new WeightedPrizeV1AllocationEngine()));

    @ParameterizedTest(name = "{0} + {1}")
    @MethodSource("supportedCombinations")
    void 네_가지_후보_상품_알고리즘_조합을_실행한다(
            DrawingAlgorithmVersion drawingVersion,
            PrizeAllocationAlgorithmVersion prizeVersion
    ) {
        DrawInput input = new DrawInput(1L, 1L, "ab".repeat(32), SEED, drawingVersion.name(), 2,
                List.of(new CandidateValue(1L, 1L), new CandidateValue(2L, 10L),
                        new CandidateValue(3L, 100L)), Set.of());

        DrawOutput winners = drawingEngine.draw(input);
        PrizeAllocationOutput prizes = prizeEngine.allocate(new PrizeAllocationInput(
                SEED, prizeVersion.name(), winners.winners(),
                List.of(new PrizeValue(1L, "A", "A", 1, 1L, 1),
                        new PrizeValue(2L, "B", "B", 2, 9L, 1))));

        assertThat(winners.algorithmVersion()).isEqualTo(drawingVersion);
        assertThat(winners.winners()).hasSize(2);
        assertThat(prizes.algorithmVersion()).isEqualTo(prizeVersion);
        assertThat(prizes.allocations()).hasSize(2);
    }

    private static Stream<Arguments> supportedCombinations() {
        return Stream.of(
                Arguments.of(DrawingAlgorithmVersion.UNIFORM_V1,
                        PrizeAllocationAlgorithmVersion.PRIZE_UNIFORM_V1),
                Arguments.of(DrawingAlgorithmVersion.UNIFORM_V1,
                        PrizeAllocationAlgorithmVersion.PRIZE_WEIGHTED_V1),
                Arguments.of(DrawingAlgorithmVersion.WEIGHTED_V1,
                        PrizeAllocationAlgorithmVersion.PRIZE_UNIFORM_V1),
                Arguments.of(DrawingAlgorithmVersion.WEIGHTED_V1,
                        PrizeAllocationAlgorithmVersion.PRIZE_WEIGHTED_V1)
        );
    }
}
