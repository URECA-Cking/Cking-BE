package kr.co.cking.drawing.simulation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import kr.co.cking.drawing.domain.engine.DrawOutput;
import kr.co.cking.drawing.domain.engine.DrawWinner;
import kr.co.cking.drawing.domain.engine.DrawingAlgorithmVersion;
import kr.co.cking.drawing.domain.engine.DrawingEngine;
import kr.co.cking.drawing.domain.engine.WeightedV1DrawingEngine;
import kr.co.cking.drawing.domain.seed.DrawingSeed;
import kr.co.cking.snapshot.domain.CandidateValue;

public final class WeightedDistributionSimulation {
    public static final int REGRESSION_ITERATIONS = 10_000;
    public static final int LARGE_ITERATIONS = 200_000;
    public static final double FAMILY_ALPHA = 0.001;
    public static final DrawingSeed MASTER_SEED = DrawingSeed.from("50".repeat(32));

    private WeightedDistributionSimulation() {
    }

    public record Result(List<DistributionCheck> checks,
                         Map<String, Map<List<Long>, Long>> orderedCounts) {
        public boolean passed() {
            return checks.stream().allMatch(DistributionCheck::passed);
        }
    }

    private record Hypothesis(SimulationScenario scenario, List<Long> prefix,
                              long memberId, double probability, boolean inclusion) {
    }

    public static Result run(List<SimulationScenario> scenarios, int iterations,
                             DrawingSeed master, DrawingEngine engine) {
        if (scenarios.isEmpty() || iterations <= 0 || master == null || engine == null
                || scenarios.stream().map(SimulationScenario::id).distinct().count() != scenarios.size()) {
            throw new IllegalArgumentException("고유 시나리오와 양수 표본 수, Seed, 엔진이 필요합니다.");
        }
        // 전체 검정 개수는 관측 전 확정하여 1개 family 안에서 Bonferroni 보정한다.
        List<Hypothesis> plan = plan(scenarios);
        Map<String, Map<List<Long>, Long>> counts = new LinkedHashMap<>();
        Set<DrawingSeed> uniqueSeeds = new HashSet<>();
        for (SimulationScenario scenario : scenarios) {
            Map<List<Long>, Long> ordered = new LinkedHashMap<>();
            for (int iteration = 0; iteration < iterations; iteration++) {
                DrawingSeed seed = SimulationSeeds.derive(master, scenario.id(), iteration);
                if (!uniqueSeeds.add(seed)) {
                    throw new IllegalStateException("추첨 Seed가 중복되어 표본을 집계할 수 없습니다.");
                }
                DrawOutput output = engine.draw(scenario.input(seed));
                verifyContract(scenario, output);
                ordered.merge(output.winners().stream().map(DrawWinner::memberId).toList(),
                        1L, Long::sum);
            }
            counts.put(scenario.id(), ordered);
        }
        List<DistributionCheck> checks = new ArrayList<>();
        for (Hypothesis hypothesis : plan) {
            long trials = 0;
            long observed = 0;
            for (var entry : counts.get(hypothesis.scenario().id()).entrySet()) {
                List<Long> order = entry.getKey();
                if (hypothesis.inclusion()) {
                    trials += entry.getValue();
                    if (order.contains(hypothesis.memberId())) {
                        observed += entry.getValue();
                    }
                } else if (order.subList(0, hypothesis.prefix().size()).equals(hypothesis.prefix())) {
                    trials += entry.getValue();
                    if (order.get(hypothesis.prefix().size()) == hypothesis.memberId()) {
                        observed += entry.getValue();
                    }
                }
            }
            String metric = hypothesis.inclusion() ? "inclusion"
                    : hypothesis.prefix().isEmpty() ? "first" : "after-" + orderText(hypothesis.prefix());
            checks.add(DistributionCheck.evaluate(hypothesis.scenario().id(), metric,
                    hypothesis.memberId(), trials, observed, hypothesis.probability(),
                    FAMILY_ALPHA, plan.size()));
        }
        return new Result(List.copyOf(checks), counts);
    }

    private static List<Hypothesis> plan(List<SimulationScenario> scenarios) {
        List<Hypothesis> result = new ArrayList<>();
        for (SimulationScenario scenario : scenarios) {
            Set<List<Long>> prefixes = new LinkedHashSet<>();
            WithoutReplacementReference.orderedProbabilities(scenario).keySet().forEach(order -> {
                for (int length = 0; length < scenario.winnerCount(); length++) {
                    prefixes.add(List.copyOf(order.subList(0, length)));
                }
            });
            for (List<Long> prefix : prefixes) {
                List<CandidateValue> remaining = scenario.eligible().stream()
                        .filter(c -> !prefix.contains(c.memberId())).toList();
                long total = 0;
                for (CandidateValue candidate : remaining) {
                    total = Math.addExact(total, candidate.ticketCount());
                }
                for (CandidateValue candidate : remaining) {
                    result.add(new Hypothesis(scenario, prefix, candidate.memberId(),
                            (double) candidate.ticketCount() / total, false));
                }
            }
            if (scenario.winnerCount() > 1) {
                WithoutReplacementReference.inclusionProbabilities(scenario).forEach((id, probability) ->
                        result.add(new Hypothesis(scenario, List.of(), id, probability, true)));
            }
        }
        return result;
    }

    private static void verifyContract(SimulationScenario scenario, DrawOutput output) {
        if (output == null || output.algorithmVersion() != DrawingAlgorithmVersion.WEIGHTED_V1
                || output.winners().size() != scenario.winnerCount()) {
            throw new IllegalStateException("알고리즘/당첨자 수 계약 위반: " + scenario.id());
        }
        Set<Long> selected = new HashSet<>();
        for (int index = 0; index < output.winners().size(); index++) {
            DrawWinner winner = output.winners().get(index);
            CandidateValue candidate = scenario.eligible().stream()
                    .filter(c -> c.memberId().equals(winner.memberId())).findFirst().orElse(null);
            if (!selected.add(winner.memberId()) || candidate == null || winner.rank() != index + 1
                    || winner.appliedTicketCount() != candidate.ticketCount()) {
                throw new IllegalStateException("중복/제외/순위/가중치 계약 위반: " + scenario.id());
            }
        }
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 1) {
            throw new IllegalArgumentException("결과 저장 디렉터리 하나를 지정해야 합니다.");
        }
        Instant started = Instant.now();
        List<SimulationScenario> scenarios = SimulationScenario.standardScenarios();
        Result result = run(scenarios, LARGE_ITERATIONS, MASTER_SEED, new WeightedV1DrawingEngine());
        Path directory = Path.of(args[0]);
        Files.createDirectories(directory);
        writeReport(directory, scenarios, result, started);
        System.out.printf(Locale.ROOT, "WEIGHTED_V1: %s, %d draws, %d checks; %s%n",
                result.passed() ? "PASS" : "FAIL", (long) LARGE_ITERATIONS * scenarios.size(),
                result.checks().size(), directory.toAbsolutePath());
        if (!result.passed()) {
            throw new IllegalStateException("확률 분포 검증 실패. 원본 CSV를 확인하세요.");
        }
    }

    private static void writeReport(Path directory, List<SimulationScenario> scenarios,
                                    Result result, Instant started) throws IOException {
        StringBuilder csv = new StringBuilder("scenario,metric,member_id,trials,observed,expected_probability,"
                + "expected_count,observed_probability,absolute_error,acceptance_lower,acceptance_upper,"
                + "bernstein_tail_bound,adjusted_alpha,low_expected_count,status\n");
        for (DistributionCheck check : result.checks()) {
            csv.append(String.format(Locale.ROOT,
                    "%s,%s,%d,%d,%d,%.17g,%.17g,%.17g,%.17g,%.17g,%.17g,%.17g,%.17g,%s,%s%n",
                    check.scenario(), check.metric(), check.memberId(), check.trials(), check.observed(),
                    check.expectedProbability(), check.trials() * check.expectedProbability(),
                    check.trials() == 0 ? Double.NaN : (double) check.observed() / check.trials(),
                    check.absoluteError(), check.acceptanceLower(), check.acceptanceUpper(),
                    check.tailBound(), check.adjustedAlpha(),
                    check.trials() * check.expectedProbability() < 5, check.status()));
        }
        StringBuilder orders = new StringBuilder("scenario,order,theoretical_probability,observed,trials\n");
        StringBuilder inputs = new StringBuilder("scenario,candidates_member_id:weight,excluded_ids,winner_count,iterations\n");
        for (SimulationScenario scenario : scenarios) {
            WithoutReplacementReference.orderedProbabilities(scenario).forEach((order, probability) ->
                    orders.append(String.format(Locale.ROOT, "%s,%s,%.17g,%d,%d%n", scenario.id(),
                            orderText(order), probability,
                            result.orderedCounts().get(scenario.id()).getOrDefault(order, 0L), LARGE_ITERATIONS)));
            inputs.append(String.format(Locale.ROOT, "%s,%s,%s,%d,%d%n", scenario.id(),
                    scenario.candidates().stream().map(c -> c.memberId() + ":" + c.ticketCount())
                            .collect(Collectors.joining("|")),
                    orderText(scenario.excludedMemberIds().stream().sorted().toList()),
                    scenario.winnerCount(), LARGE_ITERATIONS));
        }
        StringBuilder environment = new StringBuilder("format=CKING_WEIGHTED_SIMULATION_V1\n")
                .append("started_utc=").append(started).append('\n')
                .append("master_seed=").append(MASTER_SEED.value()).append('\n')
                .append("iterations_per_scenario=").append(LARGE_ITERATIONS).append('\n')
                .append("family_alpha=").append(FAMILY_ALPHA).append('\n')
                .append("hypothesis_count=").append(result.checks().size()).append('\n')
                .append("result=").append(result.passed() ? "PASS" : "FAIL").append('\n');
        for (String property : List.of("java.runtime.version", "java.vm.name", "java.vendor",
                "os.name", "os.version", "os.arch")) {
            environment.append(property).append('=').append(System.getProperty(property)).append('\n');
        }
        environment.append("available_processors=").append(Runtime.getRuntime().availableProcessors()).append('\n')
                .append("max_heap_bytes=").append(Runtime.getRuntime().maxMemory()).append('\n');
        for (String className : List.of("kr.co.cking.drawing.domain.engine.WeightedV1DrawingEngine",
                "kr.co.cking.drawing.domain.engine.WeightedCandidatePool",
                "kr.co.cking.drawing.domain.seed.DeterministicRandom",
                SimulationSeeds.class.getName(), WeightedDistributionSimulation.class.getName(),
                SimulationScenario.class.getName(), DistributionCheck.class.getName(),
                WithoutReplacementReference.class.getName())) {
            environment.append("class_sha256.").append(className).append('=').append(classHash(className)).append('\n');
        }
        Files.writeString(directory.resolve("checks.csv"), csv.toString().replace("\r\n", "\n"), StandardCharsets.UTF_8);
        Files.writeString(directory.resolve("orders.csv"), orders.toString().replace("\r\n", "\n"), StandardCharsets.UTF_8);
        Files.writeString(directory.resolve("scenarios.csv"), inputs.toString().replace("\r\n", "\n"), StandardCharsets.UTF_8);
        Files.writeString(directory.resolve("environment.txt"), environment, StandardCharsets.UTF_8);
    }

    private static String orderText(List<Long> order) {
        return order.stream().map(String::valueOf).collect(Collectors.joining("-"));
    }

    private static String classHash(String className) throws IOException {
        try (var stream = WeightedDistributionSimulation.class.getResourceAsStream(
                "/" + className.replace('.', '/') + ".class")) {
            if (stream == null) {
                throw new IOException("실행 클래스 바이트를 찾을 수 없습니다: " + className);
            }
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(stream.readAllBytes()));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
