package kr.co.cking.drawing.domain.hash;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import kr.co.cking.drawing.domain.engine.DrawOutput;
import kr.co.cking.drawing.domain.engine.DrawWinner;
import kr.co.cking.drawing.domain.engine.DrawingAlgorithmVersion;
import org.junit.jupiter.api.Test;

class DrawResultHashGeneratorTest {

    private static final String INPUT_HASH = "a".repeat(64);

    private final DrawResultHashGenerator generator = new DrawResultHashGenerator();

    @Test
    void Winner를_rank_ASC로_정규화하고_SHA_256_lowercase_hex를_생성한다() {
        DrawOutput output = output(List.of(
                new DrawWinner(2L, 2, 7L),
                new DrawWinner(1L, 1, 3L)
        ));

        DrawingHash hash = generator.generate(INPUT_HASH, output);

        assertThat(hash.canonicalPayload()).isEqualTo("""
                CKING_DRAW_RESULT_V1
                inputHash=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
                algorithmVersion=WEIGHTED_V1
                winners
                1,1,3
                2,2,7
                """);
        assertThat(hash.value())
                .isEqualTo("22afbb6396cb7df1e9772d386d76ef3646b3c14f97dfd51bfe078d8116ecc657")
                .matches("[0-9a-f]{64}");
    }

    @Test
    void Winner_전달_순서가_달라도_동일한_Hash를_반환한다() {
        DrawOutput ordered = output(List.of(
                new DrawWinner(1L, 1, 3L),
                new DrawWinner(2L, 2, 7L)
        ));
        DrawOutput reversed = output(List.of(
                new DrawWinner(2L, 2, 7L),
                new DrawWinner(1L, 1, 3L)
        ));

        assertThat(generator.generate(INPUT_HASH, ordered).value())
                .isEqualTo(generator.generate(INPUT_HASH, reversed).value());
    }

    @Test
    void Winner가_변경되면_Result_Hash도_변경된다() {
        DrawOutput original = output(List.of(new DrawWinner(1L, 1, 3L)));
        DrawOutput changed = output(List.of(new DrawWinner(2L, 1, 3L)));

        assertThat(generator.generate(INPUT_HASH, original).value())
                .isNotEqualTo(generator.generate(INPUT_HASH, changed).value());
    }

    @Test
    void rank가_변경되면_Result_Hash도_변경된다() {
        DrawOutput original = output(List.of(new DrawWinner(1L, 1, 3L)));
        DrawOutput changed = output(List.of(new DrawWinner(1L, 2, 3L)));

        assertThat(generator.generate(INPUT_HASH, original).value())
                .isNotEqualTo(generator.generate(INPUT_HASH, changed).value());
    }

    @Test
    void appliedTicketCount가_변경되면_Result_Hash도_변경된다() {
        DrawOutput original = output(List.of(new DrawWinner(1L, 1, 3L)));
        DrawOutput changed = output(List.of(new DrawWinner(1L, 1, 4L)));

        assertThat(generator.generate(INPUT_HASH, original).value())
                .isNotEqualTo(generator.generate(INPUT_HASH, changed).value());
    }

    @Test
    void 중복_rank나_memberId가_있으면_Result_Hash를_생성하지_않는다() {
        DrawOutput duplicateRank = output(List.of(
                new DrawWinner(1L, 1, 3L),
                new DrawWinner(2L, 1, 7L)
        ));
        DrawOutput duplicateMember = output(List.of(
                new DrawWinner(1L, 1, 3L),
                new DrawWinner(1L, 2, 3L)
        ));

        assertThatThrownBy(() -> generator.generate(INPUT_HASH, duplicateRank))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("rank");
        assertThatThrownBy(() -> generator.generate(INPUT_HASH, duplicateMember))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("memberId");
    }

    @Test
    void inputHash가_lowercase_SHA_256_형식이_아니면_거부한다() {
        DrawOutput output = output(List.of(new DrawWinner(1L, 1, 3L)));

        assertThatThrownBy(() -> generator.generate("A".repeat(64), output))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("inputHash");
    }

    private DrawOutput output(List<DrawWinner> winners) {
        return new DrawOutput(DrawingAlgorithmVersion.WEIGHTED_V1, winners);
    }
}
