package kr.co.cking.drawing.domain.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Set;
import kr.co.cking.drawing.domain.seed.DrawingSeed;
import kr.co.cking.snapshot.domain.CandidateValue;
import org.junit.jupiter.api.Test;

class UniformV1DrawingEngineTest {
    private static final DrawingSeed SEED = DrawingSeed.from("23".repeat(32));
    private static final String SNAPSHOT_HASH = "ab".repeat(32);
    private final DrawingEngine engine = new UniformV1DrawingEngine();

    @Test
    void 응모권_수가_달라도_같은_Seed에서_같은_후보를_선정한다() {
        DrawOutput first = engine.draw(input(List.of(
                new CandidateValue(1L, 1L),
                new CandidateValue(2L, 2L),
                new CandidateValue(3L, 3L),
                new CandidateValue(4L, 4L)
        ), Set.of()));
        DrawOutput changedWeights = engine.draw(input(List.of(
                new CandidateValue(1L, 1000L),
                new CandidateValue(2L, 1L),
                new CandidateValue(3L, 9999L),
                new CandidateValue(4L, 2L)
        ), Set.of()));

        assertThat(first.winners()).extracting(DrawWinner::memberId)
                .containsExactlyElementsOf(changedWeights.winners().stream().map(DrawWinner::memberId).toList());
        assertThat(first.algorithmVersion()).isEqualTo(DrawingAlgorithmVersion.UNIFORM_V1);
    }

    @Test
    void 제외_대상을_빼고_중복_없이_winnerCount만큼_선정한다() {
        DrawOutput output = engine.draw(input(List.of(
                new CandidateValue(1L, 1L),
                new CandidateValue(2L, 1L),
                new CandidateValue(3L, 1L),
                new CandidateValue(4L, 1L)
        ), Set.of(2L)));

        assertThat(output.winners()).hasSize(3);
        assertThat(output.winners()).extracting(DrawWinner::memberId)
                .doesNotContain(2L)
                .doesNotHaveDuplicates();
    }

    @Test
    void WEIGHTED_V1_입력은_거부한다() {
        DrawInput input = new DrawInput(1L, 1L, SNAPSHOT_HASH, SEED, "WEIGHTED_V1", 1,
                List.of(new CandidateValue(1L, 1L)), Set.of());

        assertThatThrownBy(() -> engine.draw(input))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("UNIFORM_V1");
    }

    private DrawInput input(List<CandidateValue> candidates, Set<Long> exclusions) {
        return new DrawInput(1L, 1L, SNAPSHOT_HASH, SEED, "UNIFORM_V1", 3, candidates, exclusions);
    }
}
