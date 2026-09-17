package kr.co.cking.drawing.benchmark;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import kr.co.cking.drawing.domain.engine.DrawInput;
import kr.co.cking.drawing.domain.engine.DrawOutput;
import kr.co.cking.drawing.domain.engine.DrawingAlgorithmVersion;
import kr.co.cking.drawing.domain.engine.DrawingEngine;
import kr.co.cking.drawing.domain.engine.WeightedV1DrawingEngine;
import kr.co.cking.drawing.domain.seed.DrawingSeed;
import kr.co.cking.snapshot.domain.CandidateValue;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.infra.Blackhole;

public class DrawingEngineBenchmark {

    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    @OutputTimeUnit(TimeUnit.SECONDS)
    public void drawThroughput(BenchmarkState state, Blackhole blackhole) {
        blackhole.consume(state.draw());
    }

    @Benchmark
    @BenchmarkMode(Mode.SampleTime)
    @OutputTimeUnit(TimeUnit.MILLISECONDS)
    public void drawLatency(BenchmarkState state, Blackhole blackhole) {
        blackhole.consume(state.draw());
    }

    @State(Scope.Thread)
    public static class BenchmarkState {

        private static final DrawingSeed FIXED_SEED = DrawingSeed.from("42".repeat(32));
        private static final String SNAPSHOT_HASH = "ab".repeat(32);

        @Param({"1000", "10000", "100000"})
        public int candidateCount;

        @Param({"1", "10", "100"})
        public int winnerCount;

        private DrawingEngine drawingEngine;
        private DrawInput input;

        @Setup(Level.Trial)
        public void setUp() {
            drawingEngine = new WeightedV1DrawingEngine();
            input = new DrawInput(
                    1L,
                    1L,
                    SNAPSHOT_HASH,
                    FIXED_SEED,
                    DrawingAlgorithmVersion.WEIGHTED_V1.name(),
                    winnerCount,
                    candidates(candidateCount),
                    Set.of()
            );
        }

        public DrawOutput draw() {
            return drawingEngine.draw(input);
        }

        private List<CandidateValue> candidates(int size) {
            List<CandidateValue> candidates = new ArrayList<>(size);
            for (long memberId = 1L; memberId <= size; memberId++) {
                long ticketCount = 1L + (memberId % 100L);
                candidates.add(new CandidateValue(memberId, ticketCount));
            }
            return List.copyOf(candidates);
        }
    }
}
