package kr.co.cking.snapshot.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import kr.co.cking.snapshot.domain.CandidateValue;
import org.junit.jupiter.api.Test;

class SnapshotHashGeneratorTest {

    private final SnapshotHashGenerator generator = new SnapshotHashGenerator();

    @Test
    void 후보_입력_순서와_관계없이_동일한_해시를_생성한다() {
        SnapshotHash first = generator.generate(input(List.of(
                new CandidateValue(2L, 7L),
                new CandidateValue(1L, 3L)
        )));
        SnapshotHash second = generator.generate(input(List.of(
                new CandidateValue(1L, 3L),
                new CandidateValue(2L, 7L)
        )));

        assertThat(first.value())
                .isEqualTo("a3a997ca2bed6ff1ad71484b6d13cc7a07dec9b0260c5bb040c55ddcb87ec281")
                .isEqualTo(second.value());
        assertThat(first.canonicalPayload()).isEqualTo("""
                CKING_SNAPSHOT_V1
                eventId=10
                winnerCount=2
                drawMethod=WEIGHTED
                algorithmVersion=WEIGHTED_V1
                candidates
                1,3
                2,7
                """);
    }

    @Test
    void 후보의_응모권_수가_변경되면_해시도_변경된다() {
        SnapshotHash original = generator.generate(input(List.of(new CandidateValue(1L, 3L))));
        SnapshotHash changed = generator.generate(input(List.of(new CandidateValue(1L, 4L))));

        assertThat(original.value()).isNotEqualTo(changed.value());
    }

    @Test
    void 동일한_memberId가_두_번_포함되면_거부한다() {
        SnapshotHashInput input = input(List.of(
                new CandidateValue(1L, 3L),
                new CandidateValue(1L, 7L)
        ));

        assertThatThrownBy(() -> generator.generate(input))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("memberId");
    }

    private SnapshotHashInput input(List<CandidateValue> candidates) {
        return new SnapshotHashInput(10L, 2, "WEIGHTED", "WEIGHTED_V1", candidates);
    }
}
