package kr.co.cking.snapshot.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class DrawSnapshotTest {

    private static final String HASH = "a".repeat(64);

    @Test
    void 정규화된_후보의_집계값을_계산한다() {
        DrawSnapshot snapshot = DrawSnapshot.create(
                1L,
                2,
                "WEIGHTED",
                "WEIGHTED_V1",
                HASH,
                List.of(new CandidateValue(1L, 3L), new CandidateValue(2L, 7L))
        );

        assertThat(snapshot.getCandidateCount()).isEqualTo(2);
        assertThat(snapshot.getTotalTicketCount()).isEqualTo(10L);
        assertThat(snapshot.getVerificationStatus()).isEqualTo(SnapshotVerificationStatus.UNVERIFIED);
        assertThat(snapshot.getCandidates())
                .extracting(DrawSnapshotCandidate::getMemberId)
                .containsExactly(1L, 2L);
    }

    @Test
    void 정렬되지_않은_후보는_거부한다() {
        List<CandidateValue> candidates = List.of(
                new CandidateValue(2L, 7L),
                new CandidateValue(1L, 3L)
        );

        assertThatThrownBy(() -> DrawSnapshot.create(
                1L,
                2,
                "WEIGHTED",
                "WEIGHTED_V1",
                HASH,
                candidates
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("memberId ASC");
    }

    @Test
    void 후보_목록은_외부에서_수정할_수_없다() {
        DrawSnapshot snapshot = DrawSnapshot.create(
                1L,
                1,
                "WEIGHTED",
                "WEIGHTED_V1",
                HASH,
                List.of(new CandidateValue(1L, 3L))
        );

        assertThatThrownBy(() -> snapshot.getCandidates().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
