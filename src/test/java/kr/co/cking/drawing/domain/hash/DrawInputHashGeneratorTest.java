package kr.co.cking.drawing.domain.hash;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import kr.co.cking.drawing.domain.engine.DrawInput;
import kr.co.cking.drawing.domain.engine.DrawingAlgorithmVersion;
import kr.co.cking.drawing.domain.seed.DrawingSeed;
import kr.co.cking.snapshot.domain.CandidateValue;
import org.junit.jupiter.api.Test;

class DrawInputHashGeneratorTest {

    private static final String SNAPSHOT_HASH = "ab".repeat(32);
    private static final DrawingSeed SEED = DrawingSeed.from("01".repeat(32));

    private final DrawInputHashGenerator generator = new DrawInputHashGenerator();

    @Test
    void 입력을_고정된_형식으로_정규화하고_SHA_256_lowercase_hex를_생성한다() {
        DrawingHash hash = generator.generate(input(
                List.of(new CandidateValue(2L, 7L), new CandidateValue(1L, 3L)),
                Set.of(4L, 3L)
        ));

        assertThat(hash.canonicalPayload()).isEqualTo("""
                CKING_DRAW_INPUT_V1
                eventId=10
                snapshotId=20
                snapshotHash=abababababababababababababababababababababababababababababababab
                seed=0101010101010101010101010101010101010101010101010101010101010101
                algorithmVersion=WEIGHTED_V1
                winnerCount=2
                candidates
                1,3
                2,7
                excludedMemberIds
                3
                4
                """);
        assertThat(hash.value())
                .isEqualTo("d09cdf4de6c7e737db35653f1d4ebca18aca5d52546cfa2d08c26f58a94cd969")
                .matches("[0-9a-f]{64}");
    }

    @Test
    void 동일한_입력은_반복해서_생성해도_동일한_Hash를_반환한다() {
        DrawInput input = input(candidates(), Set.of(3L, 4L));

        assertThat(generator.generate(input)).isEqualTo(generator.generate(input));
    }

    @Test
    void Candidate와_제외_대상_순서가_달라도_동일한_Hash를_반환한다() {
        DrawInput first = input(
                List.of(new CandidateValue(1L, 3L), new CandidateValue(2L, 7L)),
                Set.of(3L, 4L)
        );
        DrawInput second = input(
                List.of(new CandidateValue(2L, 7L), new CandidateValue(1L, 3L)),
                new LinkedHashSet<>(List.of(4L, 3L))
        );

        assertThat(generator.generate(first).value())
                .isEqualTo(generator.generate(second).value());
    }

    @Test
    void 빈_목록도_섹션_헤더와_마지막_LF를_유지한다() {
        DrawInput input = input(List.of(), Set.of());

        assertThat(generator.canonicalize(input)).endsWith("candidates\nexcludedMemberIds\n");
    }

    @Test
    void 입력이_null이면_Hash를_생성하지_않는다() {
        assertThatThrownBy(() -> generator.generate(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("필수");
    }

    private DrawInput input(List<CandidateValue> candidates, Set<Long> excludedMemberIds) {
        return new DrawInput(
                10L,
                20L,
                SNAPSHOT_HASH,
                SEED,
                DrawingAlgorithmVersion.WEIGHTED_V1.name(),
                2,
                candidates,
                excludedMemberIds
        );
    }

    private List<CandidateValue> candidates() {
        return List.of(
                new CandidateValue(1L, 3L),
                new CandidateValue(2L, 7L)
        );
    }
}
