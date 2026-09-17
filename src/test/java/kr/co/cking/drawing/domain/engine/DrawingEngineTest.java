package kr.co.cking.drawing.domain.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import kr.co.cking.drawing.domain.seed.DeterministicRandom;
import kr.co.cking.drawing.domain.seed.DrawingSeed;
import kr.co.cking.snapshot.domain.CandidateValue;
import org.junit.jupiter.api.Test;

class DrawingEngineTest {

    private static final DrawingSeed FIRST_SEED = DrawingSeed.from("00".repeat(32));
    private static final DrawingSeed SECOND_SEED = DrawingSeed.from("01".repeat(32));
    private static final String SNAPSHOT_HASH = "ab".repeat(32);

    private final DrawingEngine drawingEngine = new WeightedV1DrawingEngine();

    @Test
    void 동일한_입력과_Seed로_재실행하면_동일한_당첨자와_순위를_반환한다() {
        DrawInput input = input(FIRST_SEED, 3, candidates(), Set.of());

        DrawOutput first = drawingEngine.draw(input);
        DrawOutput second = drawingEngine.draw(input);

        assertThat(first).isEqualTo(second);
        assertThat(first.winners()).hasSize(3);
        assertThat(first.winners())
                .extracting(DrawWinner::rank)
                .containsExactly(1, 2, 3);
    }

    @Test
    void Candidate_입력_순서가_달라도_동일한_결과를_반환한다() {
        DrawInput ordered = input(FIRST_SEED, 3, candidates(), Set.of());
        DrawInput shuffled = input(FIRST_SEED, 3, List.of(
                new CandidateValue(4L, 13L),
                new CandidateValue(2L, 5L),
                new CandidateValue(1L, 2L),
                new CandidateValue(3L, 8L)
        ), Set.of());

        assertThat(drawingEngine.draw(shuffled)).isEqualTo(drawingEngine.draw(ordered));
    }

    @Test
    void 다른_Seed를_사용하면_당첨_결과가_변경된다() {
        DrawOutput first = drawingEngine.draw(input(FIRST_SEED, 3, candidates(), Set.of()));
        DrawOutput second = drawingEngine.draw(input(SECOND_SEED, 3, candidates(), Set.of()));

        assertThat(first.winners()).isNotEqualTo(second.winners());
    }

    @Test
    void 기존_누적_선형_방식과_동일한_당첨_결과를_유지한다() {
        List<DrawingSeed> seeds = List.of(
                FIRST_SEED,
                SECOND_SEED,
                DrawingSeed.from("ab".repeat(32))
        );

        for (DrawingSeed seed : seeds) {
            DrawInput input = input(seed, 3, candidates(), Set.of());

            assertThat(drawingEngine.draw(input).winners())
                    .containsExactlyElementsOf(drawWithLinearScan(input));
        }
    }

    @Test
    void 제외_대상은_추첨_후보에서_제거한다() {
        DrawOutput output = drawingEngine.draw(input(FIRST_SEED, 2, candidates(), Set.of(2L, 4L)));

        assertThat(output.winners())
                .extracting(DrawWinner::memberId)
                .containsExactlyInAnyOrder(1L, 3L);
    }

    @Test
    void 후보가_충분하면_winnerCount만큼_중복_없이_반환한다() {
        DrawOutput output = drawingEngine.draw(input(FIRST_SEED, 4, candidates(), Set.of()));

        assertThat(output.winners()).hasSize(4);
        assertThat(output.winners())
                .extracting(DrawWinner::memberId)
                .doesNotHaveDuplicates();
    }

    @Test
    void 제외_후_후보_수보다_winnerCount가_크면_추첨하지_않는다() {
        DrawInput input = input(FIRST_SEED, 3, candidates(), Set.of(1L, 2L));

        assertThatThrownBy(() -> drawingEngine.draw(input))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("후보 수");
    }

    @Test
    void ticketCount가_0_이하이면_입력을_거부한다() {
        assertThatThrownBy(() -> new CandidateValue(1L, 0L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ticketCount");
    }

    @Test
    void 동일한_memberId가_중복되면_입력을_거부한다() {
        List<CandidateValue> duplicated = List.of(
                new CandidateValue(1L, 2L),
                new CandidateValue(1L, 3L)
        );

        assertThatThrownBy(() -> input(FIRST_SEED, 1, duplicated, Set.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("중복");
    }

    @Test
    void 지원하지_않는_algorithmVersion이면_추첨하지_않는다() {
        DrawInput input = new DrawInput(
                10L,
                1L,
                SNAPSHOT_HASH,
                FIRST_SEED,
                "WEIGHTED_V2",
                1,
                candidates(),
                Set.of()
        );

        assertThatThrownBy(() -> drawingEngine.draw(input))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("지원하지 않는 algorithmVersion");
    }

    @Test
    void 가중치_합계가_long_범위를_초과하면_추첨하지_않는다() {
        List<CandidateValue> overflowCandidates = List.of(
                new CandidateValue(1L, Long.MAX_VALUE),
                new CandidateValue(2L, 1L)
        );

        assertThatThrownBy(() -> drawingEngine.draw(
                input(FIRST_SEED, 1, overflowCandidates, Set.of())
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("long 범위");
    }

    @Test
    void 당첨자_목록은_외부에서_수정할_수_없다() {
        DrawOutput output = drawingEngine.draw(input(FIRST_SEED, 1, candidates(), Set.of()));

        assertThatThrownBy(() -> output.winners().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void DrawInput은_후보와_제외_대상을_방어적으로_복사한다() {
        List<CandidateValue> candidates = new java.util.ArrayList<>(candidates());
        Set<Long> exclusions = new HashSet<>(Set.of(4L));
        DrawInput input = input(FIRST_SEED, 1, candidates, exclusions);

        candidates.clear();
        exclusions.clear();

        assertThat(input.candidates()).hasSize(4);
        assertThat(input.excludedMemberIds()).containsExactly(4L);
    }

    private DrawInput input(
            DrawingSeed seed,
            int winnerCount,
            List<CandidateValue> candidates,
            Set<Long> excludedMemberIds
    ) {
        return new DrawInput(
                10L,
                1L,
                SNAPSHOT_HASH,
                seed,
                DrawingAlgorithmVersion.WEIGHTED_V1.name(),
                winnerCount,
                candidates,
                excludedMemberIds
        );
    }

    private List<DrawWinner> drawWithLinearScan(DrawInput input) {
        List<CandidateValue> drawingPool = new ArrayList<>(input.candidates());
        DeterministicRandom random = new DeterministicRandom(input.seed());
        List<DrawWinner> winners = new ArrayList<>();

        for (int rank = 1; rank <= input.winnerCount(); rank++) {
            long totalWeight = drawingPool.stream()
                    .mapToLong(CandidateValue::ticketCount)
                    .sum();
            long selectedWeight = random.nextLong(totalWeight);
            long cumulativeWeight = 0L;

            for (int index = 0; index < drawingPool.size(); index++) {
                cumulativeWeight += drawingPool.get(index).ticketCount();
                if (selectedWeight < cumulativeWeight) {
                    CandidateValue winner = drawingPool.remove(index);
                    winners.add(new DrawWinner(winner.memberId(), rank, winner.ticketCount()));
                    break;
                }
            }
        }

        return winners;
    }

    private List<CandidateValue> candidates() {
        return List.of(
                new CandidateValue(1L, 2L),
                new CandidateValue(2L, 5L),
                new CandidateValue(3L, 8L),
                new CandidateValue(4L, 13L)
        );
    }
}
