package kr.co.cking.drawing.domain.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;
import kr.co.cking.drawing.domain.hash.DrawInputHashGenerator;
import kr.co.cking.drawing.domain.hash.DrawResultHashGenerator;
import kr.co.cking.drawing.domain.seed.DeterministicRandom;
import kr.co.cking.drawing.domain.seed.DrawingSeed;
import kr.co.cking.snapshot.domain.CandidateValue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class WeightedDrawingBoundaryTest {

    private static final DrawingSeed SEED = DrawingSeed.from("ab".repeat(32));
    private final WeightedV1DrawingEngine engine = new WeightedV1DrawingEngine();

    @ParameterizedTest
    @ValueSource(ints = {1, 3, 7, 8, 9, 17})
    void long_최대_총합에서_연속_제거와_모든_구간_경계를_선형_기준과_비교한다(int size) {
        assertPoolBoundaries(maximumTotalCandidates(size));
    }

    @Test
    void 매우_큰_가중치와_작은_가중치가_섞여도_연속_제거_경계가_정확하다() {
        List<CandidateValue> candidates = List.of(
                new CandidateValue(1L, 1L),
                new CandidateValue(2L, 2_147_483_648L),
                new CandidateValue(3L, 3L),
                new CandidateValue(4L, Long.MAX_VALUE - 11_147_483_655L),
                new CandidateValue(5L, 1L),
                new CandidateValue(6L, 9_000_000_000L),
                new CandidateValue(7L, 2L));
        assertPoolBoundaries(candidates);
        assertMatchesReference(input(candidates, candidates.size(), Set.of(), SEED));
    }

    private void assertPoolBoundaries(List<CandidateValue> candidates) {
        WeightedCandidatePool pool = new WeightedCandidatePool(candidates);
        LinearReference reference = new LinearReference(candidates);
        assertThat(reference.total()).isEqualTo(BigInteger.valueOf(Long.MAX_VALUE));
        List<Long> selections = new ArrayList<>();
        List<Long> winners = new ArrayList<>();
        DeterministicRandom random = new DeterministicRandom(SEED);

        while (!reference.candidates.isEmpty()) {
            assertThat(pool.totalWeight()).isEqualTo(reference.total().longValueExact());
            // 이전 제거 순서를 재생하므로 Fenwick Tree 안에 0인 구간이 남은 상태도 검사한다.
            BigInteger prefix = BigInteger.ZERO;
            for (CandidateValue candidate : reference.candidates) {
                assertBoundary(candidates, selections, prefix.longValueExact(), candidate);
                prefix = prefix.add(BigInteger.valueOf(candidate.ticketCount()));
                assertBoundary(candidates, selections, prefix.subtract(BigInteger.ONE).longValueExact(), candidate);
            }

            long selected = random.nextLong(reference.total().longValueExact());
            CandidateValue expected = reference.remove(selected);
            assertThat(pool.selectAndRemove(selected)).isEqualTo(expected);
            selections.add(selected);
            winners.add(expected.memberId());
            assertThat(pool.totalWeight()).isEqualTo(reference.total().longValueExact());
        }

        assertThat(winners).hasSize(candidates.size()).doesNotHaveDuplicates();
        assertThat(pool.totalWeight()).isZero();
        assertThatThrownBy(() -> pool.selectAndRemove(0))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("가중치 합계 범위");
    }

    @Test
    void 잘못된_선택_경계는_상태를_변경하지_않는다() {
        WeightedCandidatePool pool = new WeightedCandidatePool(maximumTotalCandidates(7));
        assertThatThrownBy(() -> pool.selectAndRemove(-1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> pool.selectAndRemove(Long.MAX_VALUE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(pool.totalWeight()).isEqualTo(Long.MAX_VALUE);
        assertThat(pool.selectAndRemove(Long.MAX_VALUE - 1).memberId()).isEqualTo(7L);
    }

    @ParameterizedTest
    @ValueSource(longs = {2_147_483_648L, 9_000_000_000L, Long.MAX_VALUE})
    void 큰_총합의_다중_추첨은_선형_기준과_같은_순위와_가중치를_반환한다(long total) {
        List<CandidateValue> candidates = candidatesWithTotal(64, total);
        for (String seedByte : List.of("00", "01", "ab", "ff")) {
            for (int winnerCount : new int[]{1, 7, 64}) {
                DrawInput input = input(candidates, winnerCount, Set.of(), DrawingSeed.from(seedByte.repeat(32)));
                assertMatchesReference(input);
            }
        }
    }

    @Test
    void 제외_전_총합은_넘쳐도_제외_후_long_최대_총합이면_추첨한다() {
        List<CandidateValue> candidates = new ArrayList<>(maximumTotalCandidates(7));
        candidates.add(new CandidateValue(8L, Long.MAX_VALUE));
        DrawInput input = input(candidates, 7, Set.of(8L), SEED);
        assertMatchesReference(input);
        assertThat(engine.draw(input)).isEqualTo(engine.draw(input(maximumTotalCandidates(7), 7, Set.of(), SEED)));
    }

    @Test
    void 제외_후에도_총합이_long_범위를_넘으면_명시적으로_거부한다() {
        List<CandidateValue> candidates = new ArrayList<>(maximumTotalCandidates(7));
        candidates.add(new CandidateValue(8L, 1L));
        candidates.add(new CandidateValue(9L, Long.MAX_VALUE));
        DrawInput input = input(candidates, 1, Set.of(9L), SEED);
        BigInteger eligibleTotal = new LinearReference(candidates.subList(0, 8)).total();
        assertThat(eligibleTotal).isEqualTo(BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE));
        assertThatThrownBy(() -> engine.draw(input))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("long 범위").hasCauseInstanceOf(ArithmeticException.class);
    }

    private void assertBoundary(List<CandidateValue> candidates, List<Long> selections,
            long selected, CandidateValue expected) {
        WeightedCandidatePool replay = new WeightedCandidatePool(candidates);
        selections.forEach(replay::selectAndRemove);
        assertThat(replay.selectAndRemove(selected)).isEqualTo(expected);
    }

    private void assertMatchesReference(DrawInput input) {
        LinearReference reference = new LinearReference(input.candidates().stream()
                .filter(candidate -> !input.excludedMemberIds().contains(candidate.memberId())).toList());
        DeterministicRandom random = new DeterministicRandom(input.seed());
        List<DrawWinner> expected = new ArrayList<>();
        for (int rank = 1; rank <= input.winnerCount(); rank++) {
            CandidateValue candidate = reference.remove(random.nextLong(reference.total().longValueExact()));
            expected.add(new DrawWinner(candidate.memberId(), rank, candidate.ticketCount()));
        }
        DrawOutput actual = engine.draw(input);
        assertThat(actual.algorithmVersion()).isEqualTo(DrawingAlgorithmVersion.WEIGHTED_V1);
        assertThat(actual.winners()).containsExactlyElementsOf(expected);
        assertThat(actual.winners()).extracting(DrawWinner::memberId).doesNotHaveDuplicates();
        assertThat(engine.draw(input)).isEqualTo(actual);
        String inputHash = new DrawInputHashGenerator().generate(input).value();
        DrawResultHashGenerator hashes = new DrawResultHashGenerator();
        assertThat(hashes.generate(inputHash, actual))
                .isEqualTo(hashes.generate(inputHash, new DrawOutput(DrawingAlgorithmVersion.WEIGHTED_V1, expected)));
    }

    private static List<CandidateValue> maximumTotalCandidates(int size) {
        return candidatesWithTotal(size, Long.MAX_VALUE);
    }

    private static List<CandidateValue> candidatesWithTotal(int size, long total) {
        // 기준 데이터의 계산은 BigInteger로 수행하고 각 가중치만 정확히 long으로 변환한다.
        BigInteger[] division = BigInteger.valueOf(total).divideAndRemainder(BigInteger.valueOf(size));
        return IntStream.range(0, size).mapToObj(index -> new CandidateValue(index + 1L,
                division[0].add(index == size - 1 ? division[1] : BigInteger.ZERO).longValueExact())).toList();
    }

    private static DrawInput input(List<CandidateValue> candidates, int winnerCount,
            Set<Long> exclusions, DrawingSeed seed) {
        return new DrawInput(10L, 1L, "ab".repeat(32), seed, "WEIGHTED_V1", winnerCount, candidates, exclusions);
    }

    /** Tree 및 long 누적합을 사용하지 않는 독립적인 누적 선형 기준 구현. */
    private static final class LinearReference {
        private final List<CandidateValue> candidates;

        private LinearReference(List<CandidateValue> candidates) {
            this.candidates = new ArrayList<>(candidates);
        }

        private BigInteger total() {
            return candidates.stream().map(candidate -> BigInteger.valueOf(candidate.ticketCount()))
                    .reduce(BigInteger.ZERO, BigInteger::add);
        }

        private CandidateValue remove(long selected) {
            BigInteger prefix = BigInteger.ZERO;
            for (int index = 0; index < candidates.size(); index++) {
                prefix = prefix.add(BigInteger.valueOf(candidates.get(index).ticketCount()));
                if (BigInteger.valueOf(selected).compareTo(prefix) < 0) {
                    return candidates.remove(index);
                }
            }
            throw new AssertionError("기준 구현에서 선택 구간을 찾지 못했습니다.");
        }
    }
}
