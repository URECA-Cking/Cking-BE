package kr.co.cking.drawing.domain.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.management.ThreadMXBean;
import java.lang.management.ManagementFactory;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.IntStream;
import kr.co.cking.drawing.domain.seed.DrawingSeed;
import kr.co.cking.snapshot.domain.CandidateValue;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** 기본 test와 분리된 HotSpot/OpenJDK 할당량 검증. DB, Redis, 외부 API를 사용하지 않는다. */
@Tag("drawing-memory")
class WeightedDrawingMemoryTest {

    private static final int WINNERS = 100;
    private static final int WARMUPS = 20;
    private static final int SAMPLES = 7;
    private static final int OPERATIONS = 5;
    private static volatile Object sink;

    @Test
    void 티켓_총합과_후보_수를_독립적으로_변경하여_할당량을_검증한다() throws Exception {
        java.lang.management.ThreadMXBean platformBean = ManagementFactory.getThreadMXBean();
        assertThat(platformBean).as("com.sun.management.ThreadMXBean을 지원하는 JVM 필요")
                .isInstanceOf(ThreadMXBean.class);
        ThreadMXBean allocations = (ThreadMXBean) platformBean;
        assertThat(allocations.isThreadAllocatedMemorySupported()).isTrue();
        if (!allocations.isThreadAllocatedMemoryEnabled()) {
            allocations.setThreadAllocatedMemoryEnabled(true);
        }
        assertThat(Runtime.getRuntime().maxMemory()).as("drawingMemoryTest의 128MiB 힙 제한")
                .isLessThanOrEqualTo(128L * 1024 * 1024);

        List<Measurement> measurements = new ArrayList<>();
        // 후보/당첨자 수는 고정하고 합계만 변경한다. MAX 값도 데이터 계산 중 넘치지 않는다.
        for (long total : new long[]{10_000L, 10_000_000_000_000L, Long.MAX_VALUE}) {
            measureScenario(allocations, measurements, "weight", 10_000, total);
        }
        // 후보 수만 10배씩 증가시키고 후보당 가중치와 당첨자 수는 고정한다.
        for (int size : new int[]{1_000, 10_000, 100_000}) {
            measureScenario(allocations, measurements, "candidates", size, Math.multiplyExact((long) size, 1_000_000_000L));
        }

        writeReport(measurements);
        for (String scope : List.of("pool", "draw")) {
            List<Measurement> fixedCandidates = measurements.stream()
                    .filter(value -> value.axis.equals("weight") && value.scope.equals(scope)).toList();
            long minimum = fixedCandidates.stream().mapToLong(Measurement::median).min().orElseThrow();
            long maximum = fixedCandidates.stream().mapToLong(Measurement::median).max().orElseThrow();
            // 난수 rejection 및 JIT 차이에 여유를 두되 가중치 규모에 비례한 증가는 거부한다.
            assertThat(maximum - minimum).as(scope + " 고정 후보 수의 할당량 차이")
                    .isLessThanOrEqualTo(Math.max(65_536L, minimum / 20));

            List<Measurement> growingCandidates = measurements.stream()
                    .filter(value -> value.axis.equals("candidates") && value.scope.equals(scope)).toList();
            for (int index = 1; index < growingCandidates.size(); index++) {
                Measurement before = growingCandidates.get(index - 1);
                Measurement after = growingCandidates.get(index);
                long growth = after.median() - before.median();
                long extraCandidates = after.candidates - before.candidates;
                // long[]와 후보 참조 배열의 선형 증가를 넉넉한 범위로 검증한다.
                assertThat(growth).as(scope + " 후보 증가에 따른 추가 할당량")
                        .isBetween(8 * extraCandidates, 128 * extraCandidates);
            }
        }
    }

    private static void measureScenario(ThreadMXBean allocations, List<Measurement> results,
            String axis, int size, long total) {
        List<CandidateValue> candidates = candidates(size, total);
        DrawInput input = new DrawInput(10L, 1L, "ab".repeat(32), DrawingSeed.from("ab".repeat(32)),
                "WEIGHTED_V1", WINNERS, candidates, Set.of());
        WeightedV1DrawingEngine engine = new WeightedV1DrawingEngine();
        results.add(measure(allocations, axis, "pool", size, total, () -> new WeightedCandidatePool(candidates)));
        assertThat(((WeightedCandidatePool) sink).totalWeight()).isEqualTo(total);
        results.add(measure(allocations, axis, "draw", size, total, () -> engine.draw(input)));
        DrawOutput output = (DrawOutput) sink;
        assertThat(output.winners()).hasSize(WINNERS);
        assertThat(output.winners()).extracting(DrawWinner::memberId).doesNotHaveDuplicates();
        assertThat(output.winners()).extracting(DrawWinner::rank)
                .containsExactlyElementsOf(IntStream.rangeClosed(1, WINNERS).boxed().toList());
    }

    private static Measurement measure(ThreadMXBean allocations, String axis, String scope,
            int size, long total, Supplier<Object> operation) {
        for (int count = 0; count < WARMUPS; count++) {
            sink = operation.get();
        }
        long threadId = Thread.currentThread().threadId();
        long[] samples = new long[SAMPLES];
        for (int sample = 0; sample < SAMPLES; sample++) {
            long before = allocations.getThreadAllocatedBytes(threadId);
            for (int count = 0; count < OPERATIONS; count++) {
                // 결과를 외부에 보관해 측정 대상 할당이 최적화로 제거되지 않게 한다.
                sink = operation.get();
            }
            long after = allocations.getThreadAllocatedBytes(threadId);
            assertThat(before).isNotNegative();
            assertThat(after).isGreaterThanOrEqualTo(before);
            samples[sample] = (after - before) / OPERATIONS;
        }
        return new Measurement(axis, scope, size, total, samples);
    }

    private static List<CandidateValue> candidates(int size, long total) {
        BigInteger[] division = BigInteger.valueOf(total).divideAndRemainder(BigInteger.valueOf(size));
        return IntStream.range(0, size).mapToObj(index -> new CandidateValue(index + 1L,
                division[0].add(index == size - 1 ? division[1] : BigInteger.ZERO).longValueExact())).toList();
    }

    private static void writeReport(List<Measurement> measurements) throws Exception {
        String reportProperty = System.getProperty("drawing.memory.report");
        assertThat(reportProperty).as("drawingMemoryTest 태스크로 실행해야 합니다.").isNotBlank();
        Path report = Path.of(reportProperty);
        Files.createDirectories(report.getParent());
        StringBuilder csv = new StringBuilder("axis,scope,candidates,winners,total_weight,median_bytes_per_op,samples_bytes_per_op\n");
        for (Measurement measurement : measurements) {
            csv.append(measurement.axis).append(',').append(measurement.scope).append(',')
                    .append(measurement.candidates).append(',').append(WINNERS).append(',')
                    .append(measurement.total).append(',').append(measurement.median()).append(",\"")
                    .append(Arrays.toString(measurement.samples)).append("\"\n");
        }
        Files.writeString(report, csv, StandardCharsets.UTF_8);
        Files.writeString(report.resolveSibling("environment.txt"), "timestamp=" + Instant.now()
                + "\njava=" + System.getProperty("java.runtime.version")
                + "\nvm=" + System.getProperty("java.vm.name")
                + "\nos=" + System.getProperty("os.name") + " " + System.getProperty("os.arch")
                + "\nmax_heap_bytes=" + Runtime.getRuntime().maxMemory()
                + "\njvm_args=" + ManagementFactory.getRuntimeMXBean().getInputArguments()
                + "\nwarmups=" + WARMUPS + "\nsamples=" + SAMPLES + "\noperations_per_sample=" + OPERATIONS
                + "\nmetric=ThreadMXBean.getThreadAllocatedBytes, current thread, bytes/op\n", StandardCharsets.UTF_8);
        System.out.println(csv);
        System.out.println("Allocation report: " + report);
    }

    private record Measurement(String axis, String scope, int candidates, long total, long[] samples) {
        private long median() {
            long[] sorted = samples.clone();
            Arrays.sort(sorted);
            return sorted[sorted.length / 2];
        }
    }
}
