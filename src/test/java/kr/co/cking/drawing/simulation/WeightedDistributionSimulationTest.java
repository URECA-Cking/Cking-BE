package kr.co.cking.drawing.simulation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import kr.co.cking.drawing.domain.engine.DrawOutput;
import kr.co.cking.drawing.domain.engine.DrawWinner;
import kr.co.cking.drawing.domain.engine.DrawingAlgorithmVersion;
import kr.co.cking.drawing.domain.engine.WeightedV1DrawingEngine;
import kr.co.cking.drawing.domain.seed.DrawingSeed;
import kr.co.cking.snapshot.domain.CandidateValue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class WeightedDistributionSimulationTest {
    @Test
    void 고정_Seed_집합으로_일반_CI에서_확률_분포를_회귀_검증한다() {
        var result = WeightedDistributionSimulation.run(SimulationScenario.standardScenarios(),
                WeightedDistributionSimulation.REGRESSION_ITERATIONS,
                WeightedDistributionSimulation.MASTER_SEED, new WeightedV1DrawingEngine());

        assertThat(result.checks()).hasSize(69).allSatisfy(check ->
                assertThat(check.passed()).as(check.toString()).isTrue());
        assertThat(result.orderedCounts()).allSatisfy((id, counts) ->
                assertThat(counts.values().stream().mapToLong(Long::longValue).sum())
                        .isEqualTo(WeightedDistributionSimulation.REGRESSION_ITERATIONS));
    }

    @Test
    void 마스터_Seed와_시나리오와_반복_번호로_서로_다른_Seed를_재현한다() {
        Set<DrawingSeed> seeds = new HashSet<>();
        for (String id : List.of("first", "second")) {
            for (int index = 0; index < 1_000; index++) {
                DrawingSeed seed = SimulationSeeds.derive(WeightedDistributionSimulation.MASTER_SEED, id, index);
                assertThat(seeds.add(seed)).isTrue();
                assertThat(seed).isEqualTo(SimulationSeeds.derive(
                        WeightedDistributionSimulation.MASTER_SEED, id, index));
            }
        }
        assertThat(SimulationSeeds.derive(DrawingSeed.from("00".repeat(32)), "first", 0))
                .isNotIn(seeds);
    }

    @Test
    void 작은_후보군의_여섯_순서와_포함_확률은_손으로_계산한_분수와_일치한다() {
        var scenario = SimulationScenario.of("reference", 2, 1, 3, 6);
        var orders = WithoutReplacementReference.orderedProbabilities(scenario);
        assertThat(orders).hasSize(6);
        assertThat(orders.get(List.of(1L, 2L))).isCloseTo(1.0 / 30, within(1e-14));
        assertThat(orders.get(List.of(1L, 3L))).isCloseTo(1.0 / 15, within(1e-14));
        assertThat(orders.get(List.of(2L, 1L))).isCloseTo(3.0 / 70, within(1e-14));
        assertThat(orders.get(List.of(2L, 3L))).isCloseTo(9.0 / 35, within(1e-14));
        assertThat(orders.get(List.of(3L, 1L))).isCloseTo(3.0 / 20, within(1e-14));
        assertThat(orders.get(List.of(3L, 2L))).isCloseTo(9.0 / 20, within(1e-14));
        assertThat(orders.values().stream().mapToDouble(Double::doubleValue).sum())
                .isCloseTo(1, within(1e-14));
        var inclusion = WithoutReplacementReference.inclusionProbabilities(scenario);
        assertThat(inclusion.get(1L)).isCloseTo(41.0 / 140, within(1e-14));
        assertThat(inclusion.get(2L)).isCloseTo(47.0 / 60, within(1e-14));
        assertThat(inclusion.get(3L)).isCloseTo(97.0 / 105, within(1e-14));
        assertThat(inclusion.values().stream().mapToDouble(Double::doubleValue).sum())
                .isCloseTo(2, within(1e-14));
    }

    @Test
    void 모든_유효_후보를_선정하면_포함_확률은_정확히_1이다() {
        var scenario = SimulationScenario.of("all", 3, 1, 3, 6);
        assertThat(WithoutReplacementReference.inclusionProbabilities(scenario).values()).containsOnly(1.0);
    }

    @Test
    void 조건부_분모는_해당_앞선_당첨_순서가_관측된_횟수다() {
        var scenario = SimulationScenario.of("conditional", 3, 1, 2, 3, 4);
        var result = WeightedDistributionSimulation.run(List.of(scenario), 2_000,
                WeightedDistributionSimulation.MASTER_SEED, new WeightedV1DrawingEngine());
        var check = result.checks().stream().filter(c -> c.metric().equals("after-1-2")
                && c.memberId() == 3).findFirst().orElseThrow();
        long prefixCount = result.orderedCounts().get("conditional").entrySet().stream()
                .filter(e -> e.getKey().subList(0, 2).equals(List.of(1L, 2L)))
                .mapToLong(e -> e.getValue()).sum();
        assertThat(check.trials()).isEqualTo(prefixCount).isPositive().isLessThan(2_000);
        assertThat(check.expectedProbability()).isEqualTo(3.0 / 7);
    }

    @Test
    void 동일_실행은_집계와_판정을_그대로_재현한다() {
        var scenarios = List.of(SimulationScenario.of("replay", 2, 1, 3, 6));
        var first = WeightedDistributionSimulation.run(scenarios, 200,
                WeightedDistributionSimulation.MASTER_SEED, new WeightedV1DrawingEngine());
        var second = WeightedDistributionSimulation.run(scenarios, 200,
                WeightedDistributionSimulation.MASTER_SEED, new WeightedV1DrawingEngine());
        assertThat(first).isEqualTo(second);
    }

    @Test
    void 가중치를_무시하는_균등_엔진은_확률_검증을_통과하지_못한다() {
        AtomicInteger index = new AtomicInteger();
        var result = WeightedDistributionSimulation.run(
                List.of(SimulationScenario.of("biased", 1, 1, 3, 6)), 3_000,
                WeightedDistributionSimulation.MASTER_SEED, input -> {
                    CandidateValue candidate = input.candidates().get(index.getAndIncrement() % 3);
                    return new DrawOutput(DrawingAlgorithmVersion.WEIGHTED_V1,
                            List.of(new DrawWinner(candidate.memberId(), 1, candidate.ticketCount())));
                });
        assertThat(result.passed()).isFalse();
        assertThat(result.checks()).anySatisfy(check -> assertThat(check.status()).isEqualTo("FAIL"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"count", "excluded", "rank", "weight", "unknown", "algorithm"})
    void 매_반복의_결과_계약_위반을_즉시_차단한다(String violation) {
        var scenario = new SimulationScenario("contract", List.of(new CandidateValue(1L, 1),
                new CandidateValue(2L, 3), new CandidateValue(3L, 6)), 2, Set.of(3L));
        assertThatThrownBy(() -> WeightedDistributionSimulation.run(List.of(scenario), 1,
                WeightedDistributionSimulation.MASTER_SEED, input -> {
                    List<DrawWinner> winners = new ArrayList<>(List.of(
                            new DrawWinner(1L, 1, 1), new DrawWinner(2L, 2, 3)));
                    switch (violation) {
                        case "count" -> winners.removeLast();
                        case "excluded" -> winners.set(1, new DrawWinner(3L, 2, 6));
                        case "rank" -> winners.set(1, new DrawWinner(2L, 3, 3));
                        case "weight" -> winners.set(1, new DrawWinner(2L, 2, 4));
                        case "unknown" -> winners.set(1, new DrawWinner(4L, 2, 1));
                        default -> { }
                    }
                    return new DrawOutput(violation.equals("algorithm")
                            ? DrawingAlgorithmVersion.UNIFORM_V1 : DrawingAlgorithmVersion.WEIGHTED_V1, winners);
                })).isInstanceOf(IllegalStateException.class).hasMessageContaining("계약 위반");
    }

    @Test
    void 희귀_빈도는_정규_근사_없이_판정하고_표본이_없으면_통과시키지_않는다() {
        assertThat(check(200_000, 0, 1e-7, 69).passed()).isTrue();
        assertThat(check(200_000, 100, 1e-7, 69).passed()).isFalse();
        assertThat(check(0, 0, 0.5, 69).status()).isEqualTo("INSUFFICIENT");
        assertThat(check(100, 100, 1, 69).passed()).isTrue();
        assertThat(check(100, 99, 1, 69).passed()).isFalse();
        assertThat(check(100, 1, 0, 69).passed()).isFalse();
    }

    @Test
    void 전체_검정_개수에_따라_허용_오차와_유의수준을_보정한다() {
        var single = check(10_000, 5_000, 0.5, 1);
        var multiple = check(10_000, 5_000, 0.5, 69);
        assertThat(multiple.adjustedAlpha()).isEqualTo(0.001 / 69);
        assertThat(multiple.acceptanceUpper()).isGreaterThan(single.acceptanceUpper());
        double radius = (multiple.acceptanceUpper() - 0.5) * 10_000;
        double bound = 2 * Math.exp(-radius * radius / (2 * (2_500 + radius / 3)));
        assertThat(bound).isLessThanOrEqualTo(multiple.adjustedAlpha());
    }

    @Test
    void 시나리오_ID_중복과_잘못된_표본_수를_거부한다() {
        var scenario = SimulationScenario.of("duplicate", 1, 1, 3, 6);
        assertThatThrownBy(() -> WeightedDistributionSimulation.run(List.of(scenario, scenario), 10,
                WeightedDistributionSimulation.MASTER_SEED, new WeightedV1DrawingEngine()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WeightedDistributionSimulation.run(List.of(scenario), 0,
                WeightedDistributionSimulation.MASTER_SEED, new WeightedV1DrawingEngine()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static DistributionCheck check(long trials, long observed, double probability, int count) {
        return DistributionCheck.evaluate("check", "first", 1, trials, observed, probability, 0.001, count);
    }
}
