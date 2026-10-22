package kr.co.cking.drawing.domain.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.BufferedWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.Set;
import kr.co.cking.drawing.domain.engine.WeightedComparison.Limits;
import kr.co.cking.drawing.domain.engine.WeightedComparison.Method;
import kr.co.cking.drawing.domain.engine.WeightedComparison.Pool;
import kr.co.cking.drawing.domain.engine.WeightedComparison.Prepared;
import kr.co.cking.drawing.domain.seed.DrawingSeed;
import kr.co.cking.snapshot.domain.CandidateValue;
import org.junit.jupiter.api.Test;

class WeightedComparisonTest {

    private static final DrawingSeed SEED = DrawingSeed.from("0123456789abcdef".repeat(4));

    @Test
    void 비정렬_입력과_제외_대상에서_여러_Seed의_당첨_결과가_운영과_같다() {
        Random fixture = new Random(503);
        for (int trial = 0; trial < 40; trial++) {
            int count = 2 + fixture.nextInt(49);
            List<CandidateValue> candidates = new ArrayList<>();
            for (long id = 1; id <= count; id++) {
                candidates.add(new CandidateValue(id, 1 + fixture.nextInt(100)));
            }
            Collections.shuffle(candidates, fixture);
            byte[] seedBytes = new byte[32];
            fixture.nextBytes(seedBytes);
            DrawingSeed seed = DrawingSeed.fromBytes(seedBytes);
            Set<Long> excluded = Set.of(1L, 999L);
            int winners = trial % 2 == 0 ? count - 1 : 1;
            Prepared input = WeightedComparison.normalize(candidates, excluded, seed, winners);
            DrawOutput expected = new WeightedV1DrawingEngine().draw(new DrawInput(1L, 1L,
                    "0".repeat(64), seed, "WEIGHTED_V1", winners, candidates, excluded));
            for (Method method : Method.values()) {
                DrawOutput actual = WeightedComparison.select(input, WeightedComparison.build(method, input, Limits.DEFAULT));
                assertThat(actual).as("trial=%s, method=%s", trial, method).isEqualTo(expected);
                assertThat(actual.winners()).extracting(DrawWinner::memberId)
                        .doesNotContain(1L, 999L).doesNotHaveDuplicates();
            }
        }
    }

    @Test
    void 모든_가중치_구간과_선정_후_남은_구간이_일치한다() {
        Prepared input = WeightedComparison.normalize(List.of(new CandidateValue(3L, 3),
                new CandidateValue(1L, 2), new CandidateValue(2L, 5)), Set.of(), SEED, 3);
        for (Method method : Method.values()) {
            for (int offset = 0; offset < 10; offset++) {
                Pool pool = WeightedComparison.build(method, input, Limits.DEFAULT);
                CandidateValue selected = pool.selectAndRemove(offset);
                long expectedId = offset < 2 ? 1 : offset < 7 ? 2 : 3;
                assertThat(selected.memberId()).isEqualTo(expectedId);
                assertThat(pool.totalWeight()).isEqualTo(10 - selected.ticketCount());
                List<CandidateValue> remaining = input.candidates().stream()
                        .filter(candidate -> !candidate.memberId().equals(selected.memberId())).toList();
                assertThat(pool.selectAndRemove(0)).isEqualTo(remaining.getFirst());
                assertThat(pool.selectAndRemove(pool.totalWeight() - 1)).isEqualTo(remaining.getLast());
                assertThat(pool.totalWeight()).isZero();
                assertThatThrownBy(() -> pool.selectAndRemove(0)).isInstanceOf(IllegalArgumentException.class);
            }
        }
    }

    @Test
    void long_최대_가중치도_티켓_제외_후_나머지_방식에서_운영과_같다() {
        List<CandidateValue> candidates = List.of(new CandidateValue(1L, Long.MAX_VALUE - 2),
                new CandidateValue(2L, 1), new CandidateValue(3L, 1));
        Prepared input = WeightedComparison.normalize(candidates, Set.of(), SEED, 3);
        DrawOutput expected = new WeightedV1DrawingEngine().draw(new DrawInput(1L, 1L,
                "0".repeat(64), SEED, "WEIGHTED_V1", 3, candidates, Set.of()));
        for (Method method : List.of(Method.PREFIX_LINEAR, Method.PREFIX_BINARY, Method.FENWICK)) {
            assertThat(WeightedComparison.select(input, WeightedComparison.build(method, input, Limits.DEFAULT)))
                    .isEqualTo(expected);
        }
        assertThatThrownBy(() -> WeightedComparison.build(Method.TICKET_ARRAY, input, Limits.DEFAULT))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("MAX_TICKETS");
    }

    @Test
    void 티켓_개수와_바이트_상한을_배열_할당_전에_검사한다() {
        Prepared input = WeightedComparison.normalize(List.of(new CandidateValue(1L, 10)), Set.of(), SEED, 1);
        assertThat(new Limits(10, 64).exclusion(10)).isNull();
        assertThat(new Limits(9, 64).exclusion(10)).startsWith("MAX_TICKETS");
        assertThat(new Limits(10, 63).exclusion(10)).startsWith("MAX_TICKET_BYTES");
        assertThat(new Limits(Long.MAX_VALUE, Long.MAX_VALUE).exclusion(Integer.MAX_VALUE))
                .startsWith("JAVA_ARRAY_LIMIT");
        assertThatThrownBy(() -> WeightedComparison.build(Method.TICKET_ARRAY, input, new Limits(10, 63)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("MAX_TICKET_BYTES");
        assertThat(WeightedComparison.select(input, WeightedComparison.build(Method.TICKET_ARRAY, input,
                new Limits(10, 64))).winners()).containsExactly(new DrawWinner(1L, 1, 10));
    }

    @Test
    void 잘못된_후보와_제외_후_부족과_합계_오버플로를_거부한다() {
        assertThatThrownBy(() -> WeightedComparison.normalize(List.of(new CandidateValue(1L, 1),
                new CandidateValue(1L, 2)), Set.of(), SEED, 1)).hasMessageContaining("중복");
        assertThatThrownBy(() -> WeightedComparison.normalize(List.of(new CandidateValue(1L, 1)),
                Set.of(1L), SEED, 1)).hasMessageContaining("후보 수");
        assertThatThrownBy(() -> WeightedComparison.normalize(List.of(new CandidateValue(1L, Long.MAX_VALUE),
                new CandidateValue(2L, 1)), Set.of(), SEED, 1)).hasMessageContaining("long 범위");
        // 제외된 가중치는 운영과 마찬가지로 합계에 포함하지 않는다.
        assertThat(WeightedComparison.normalize(List.of(new CandidateValue(1L, Long.MAX_VALUE),
                new CandidateValue(2L, 1)), Set.of(1L), SEED, 1).totalWeight()).isEqualTo(1);
    }

    @Test
    void 비교_시나리오는_같은_후보_수의_다른_총_가중치와_제외를_포함한다() {
        var scenarios = WeightedScenarios.create(SEED);
        assertThat(scenarios).hasSize(20);
        assertThat(scenarios.stream().filter(scenario -> scenario.raw().size() == 1_000)
                .map(scenario -> scenario.prepare().totalWeight()).distinct()).hasSize(4);
        assertThat(scenarios).allSatisfy(scenario -> {
            assertThat(scenario.prepare().candidates()).isSortedAccordingTo(CandidateValue.BY_MEMBER_ID);
            assertThat(scenario.excluded()).hasSize(scenario.raw().size() / 20);
        });
    }

    @Test
    void 백분위수는_nearest_rank로_집계한다() {
        long[] values = java.util.stream.LongStream.rangeClosed(1, 20).toArray();
        assertThat(WeightedRandomBenchmark.percentile(values, 0.50)).isEqualTo(10);
        assertThat(WeightedRandomBenchmark.percentile(values, 0.95)).isEqualTo(19);
    }

    @Test
    void 티켓_제외_사유와_원본_샘플을_기록하고_나머지_방식은_계속_측정한다() throws Exception {
        StringWriter raw = new StringWriter();
        StringWriter aggregated = new StringWriter();
        try (BufferedWriter samples = new BufferedWriter(raw);
             BufferedWriter summary = new BufferedWriter(aggregated)) {
            var scenario = WeightedScenarios.create(20, 1, WeightedScenarios.Distribution.UNIFORM_100, SEED);
            WeightedRandomBenchmark.runScenario(scenario, new Limits(10, 64), 1, 2, samples, summary);
        }
        assertThat(raw.toString().lines()).hasSize(24);
        assertThat(raw.toString()).doesNotContain("TICKET_ARRAY");
        assertThat(aggregated.toString().lines()).hasSize(16);
        assertThat(aggregated.toString().lines().filter(line -> line.contains("SKIPPED")))
                .hasSize(4).allMatch(line -> line.contains("MAX_TICKETS"));
        assertThat(aggregated.toString().lines().filter(line -> line.contains(",OK,")))
                .hasSize(12).allMatch(line -> line.contains(",OK,,2,"));
    }
}
