package kr.co.cking.drawing.domain.engine;

import com.sun.management.ThreadMXBean;
import java.io.BufferedWriter;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;
import kr.co.cking.drawing.domain.engine.WeightedComparison.Limits;
import kr.co.cking.drawing.domain.engine.WeightedComparison.Method;
import kr.co.cking.drawing.domain.engine.WeightedComparison.Pool;
import kr.co.cking.drawing.domain.engine.WeightedComparison.Prepared;
import kr.co.cking.drawing.domain.engine.WeightedScenarios.Scenario;
import kr.co.cking.drawing.domain.seed.DrawingSeed;

/** 명시적으로 실행하는 단일 스레드 비교 도구. 일반 test/check/bootJar에는 포함하지 않는다. */
public final class WeightedRandomBenchmark {

    enum Phase { NORMALIZE, BUILD, SELECT, TOTAL }

    record Sample(long nanos, long bytes) { }

    private static volatile Object sink;
    private static final ThreadMXBean ALLOCATION = allocationBean();

    public static void main(String[] args) throws IOException {
        if (args.length != 6) {
            throw new IllegalArgumentException("output warmups iterations seed maxTickets maxTicketBytes가 필요합니다.");
        }
        Path output = Path.of(args[0]);
        int warmups = positive(args[1]);
        int iterations = positive(args[2]);
        DrawingSeed seed = DrawingSeed.from(args[3]);
        Limits limits = new Limits(Long.parseLong(args[4]), Long.parseLong(args[5]));
        // 기존 결과를 덮어쓰지 않는다. fork마다 별도 디렉터리를 지정한다.
        Files.createDirectory(output.toAbsolutePath());
        writeMetadata(output, warmups, iterations, seed, limits);
        try (BufferedWriter samples = writer(output.resolve("samples.csv"));
             BufferedWriter summary = writer(output.resolve("summary.csv"))) {
            samples.write("scenario,candidates,eligible,winners,total_weight,method,phase,iteration,ns,allocated_bytes\n");
            summary.write("scenario,candidates,eligible,winners,total_weight,method,phase,status,reason,samples,p50_ns,p95_ns,ops_per_second,mean_allocated_bytes\n");
            for (Scenario scenario : WeightedScenarios.create(seed)) {
                runScenario(scenario, limits, warmups, iterations, samples, summary);
                System.out.println("완료: " + scenario.name());
            }
        }
        System.out.println("원본 측정 결과: " + output.toAbsolutePath());
    }

    static void runScenario(Scenario scenario, Limits limits, int warmups, int iterations,
                                    BufferedWriter samples, BufferedWriter summary) throws IOException {
        Prepared canonical = scenario.prepare();
        DrawOutput expected = new WeightedV1DrawingEngine().draw(new DrawInput(1L, 1L,
                "0".repeat(64), scenario.seed(), "WEIGHTED_V1", scenario.winnerCount(),
                scenario.raw(), scenario.excluded()));
        for (Method method : Method.values()) {
            String prefix = scenario.name() + "," + scenario.raw().size() + ","
                    + canonical.candidates().size() + "," + scenario.winnerCount() + ","
                    + canonical.totalWeight() + "," + method + ",";
            String exclusion = method == Method.TICKET_ARRAY ? limits.exclusion(canonical.totalWeight()) : null;
            if (exclusion != null) {
                for (Phase phase : Phase.values()) {
                    summary.write(prefix + phase + ",SKIPPED," + exclusion + ",0,,,,\n");
                }
                continue;
            }
            DrawOutput actual = WeightedComparison.select(canonical, WeightedComparison.build(method, canonical, limits));
            if (!expected.equals(actual)) {
                throw new IllegalStateException("운영 결과 불일치: " + scenario.name() + "/" + method);
            }
            for (Phase phase : Phase.values()) {
                List<Sample> values = new ArrayList<>();
                for (int iteration = -warmups; iteration < iterations; iteration++) {
                    // 측정 대상 외 준비는 타이머/할당량 계측 전에 끝낸다. 매번 새 풀을 사용한다.
                    Supplier<?> operation = operation(scenario, method, phase, limits);
                    Sample sample = measure(operation);
                    if (iteration >= 0) {
                        values.add(sample);
                        samples.write(prefix + phase + "," + iteration + "," + sample.nanos()
                                + "," + sample.bytes() + "\n");
                    }
                }
                writeSummary(summary, prefix + phase, values);
            }
        }
    }

    private static Supplier<?> operation(Scenario scenario, Method method, Phase phase, Limits limits) {
        if (phase == Phase.NORMALIZE) {
            return scenario::prepare;
        }
        if (phase == Phase.TOTAL) {
            return () -> {
                Prepared input = scenario.prepare();
                return WeightedComparison.select(input, WeightedComparison.build(method, input, limits));
            };
        }
        Prepared input = scenario.prepare();
        if (phase == Phase.BUILD) {
            return () -> WeightedComparison.build(method, input, limits);
        }
        Pool pool = WeightedComparison.build(method, input, limits);
        return () -> WeightedComparison.select(input, pool);
    }

    private static Sample measure(Supplier<?> operation) {
        long bytesBefore = allocatedBytes();
        long start = System.nanoTime();
        sink = operation.get();
        long nanos = System.nanoTime() - start;
        long bytesAfter = allocatedBytes();
        return new Sample(nanos, bytesBefore < 0 ? -1 : bytesAfter - bytesBefore);
    }

    static long percentile(long[] sorted, double percentile) {
        return sorted[(int) Math.ceil(sorted.length * percentile) - 1];
    }

    private static void writeSummary(BufferedWriter output, String prefix, List<Sample> samples) throws IOException {
        long[] times = samples.stream().mapToLong(Sample::nanos).sorted().toArray();
        double meanNanos = Arrays.stream(times).average().orElseThrow();
        double meanBytes = samples.stream().anyMatch(sample -> sample.bytes() < 0) ? -1
                : samples.stream().mapToLong(Sample::bytes).average().orElseThrow();
        output.write(String.format(Locale.ROOT, "%s,OK,,%d,%d,%d,%.3f,%.1f%n", prefix,
                samples.size(), percentile(times, 0.50), percentile(times, 0.95),
                1_000_000_000.0 / meanNanos, meanBytes));
    }

    private static ThreadMXBean allocationBean() {
        if (ManagementFactory.getThreadMXBean() instanceof ThreadMXBean bean
                && bean.isThreadAllocatedMemorySupported()) {
            bean.setThreadAllocatedMemoryEnabled(true);
            return bean;
        }
        return null;
    }

    private static long allocatedBytes() {
        return ALLOCATION == null ? -1 : ALLOCATION.getThreadAllocatedBytes(Thread.currentThread().threadId());
    }

    private static int positive(String value) {
        int number = Integer.parseInt(value);
        if (number <= 0) {
            throw new IllegalArgumentException("워밍업과 반복 횟수는 양수여야 합니다.");
        }
        return number;
    }

    private static BufferedWriter writer(Path path) throws IOException {
        return Files.newBufferedWriter(path, StandardCharsets.UTF_8);
    }

    private static void writeMetadata(Path output, int warmups, int iterations, DrawingSeed seed,
                                      Limits limits) throws IOException {
        String metadata = "timestamp=" + Instant.now() + "\n"
                + "revision=" + System.getProperty("benchmark.revision", "unrecorded") + "\n"
                + "jdk=" + System.getProperty("java.runtime.version") + "\n"
                + "vm=" + System.getProperty("java.vm.name") + "\n"
                + "os=" + System.getProperty("os.name") + "/" + System.getProperty("os.arch") + "\n"
                + "cpu=" + System.getProperty("benchmark.cpu", System.getenv("PROCESSOR_IDENTIFIER")) + "\n"
                + "processors=" + Runtime.getRuntime().availableProcessors() + "\n"
                + "jvmArgs=" + ManagementFactory.getRuntimeMXBean().getInputArguments() + "\n"
                + "maxHeapBytes=" + Runtime.getRuntime().maxMemory() + "\n"
                + "warmupsPerCaseAndPhase=" + warmups + "\niterationsPerCaseAndPhase=" + iterations + "\n"
                + "seed=" + seed.value() + "\nfixtureShuffleSeed=503\n"
                + "maxTickets=" + limits.maxTickets() + "\nmaxTicketBytes=" + limits.maxTicketBytes() + "\n"
                + "threadAllocationSupported=" + (ALLOCATION != null) + "\n"
                + "throughput=1e9 / arithmetic mean ns per complete phase operation\n"
                + "percentile=nearest rank\n";
        Files.writeString(output.resolve("environment.txt"), metadata, StandardCharsets.UTF_8);
    }
}
