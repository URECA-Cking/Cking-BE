package kr.co.cking.drawing.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import kr.co.cking.snapshot.application.VerifiedSnapshot;
import org.junit.jupiter.api.Test;

class DrawingTest {

    @Test
    void INITIAL_Drawing은_READY_PRIVATE_상태로_생성된다() {
        Drawing drawing = Drawing.createInitial(
                snapshotContract(),
                3L,
                4L
        );

        assertThat(drawing.getEventId()).isEqualTo(1L);
        assertThat(drawing.getDrawNo()).isZero();
        assertThat(drawing.getDrawType()).isEqualTo(DrawingType.INITIAL);
        assertThat(drawing.getOriginalDrawingId()).isNull();
        assertThat(drawing.getRedrawRequestId()).isNull();
        assertThat(drawing.getSnapshotId()).isEqualTo(2L);
        assertThat(drawing.getSeedId()).isEqualTo(3L);
        assertThat(drawing.getDrawMethod()).isEqualTo("WEIGHTED");
        assertThat(drawing.getAlgorithmVersion()).isEqualTo("WEIGHTED_V1");
        assertThat(drawing.getWinnerCount()).isEqualTo(2);
        assertThat(drawing.getStatus()).isEqualTo(DrawingStatus.READY);
        assertThat(drawing.getVisibility()).isEqualTo(DrawingVisibility.PRIVATE);
        assertThat(drawing.getAttemptCount()).isZero();
        assertThat(drawing.getRequestedBy()).isEqualTo(4L);
    }

    @Test
    void 필수_식별자는_양수여야_한다() {
        assertThatThrownBy(() -> Drawing.createInitial(
                snapshotContract(),
                0L,
                4L
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 검증된_Snapshot은_필수다() {
        assertThatThrownBy(() -> DrawingSnapshotContract.from(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void INITIAL_Drawing의_확정_입력은_검증된_Snapshot에서_가져온다() {
        VerifiedSnapshot snapshot = verifiedSnapshot();

        Drawing drawing = Drawing.createInitial(
                DrawingSnapshotContract.from(snapshot),
                3L,
                4L
        );

        assertThat(drawing.getSnapshotId()).isEqualTo(snapshot.snapshotId());
        assertThat(drawing.getEventId()).isEqualTo(snapshot.eventId());
        assertThat(drawing.getDrawMethod()).isEqualTo(snapshot.drawMethod());
        assertThat(drawing.getAlgorithmVersion()).isEqualTo(snapshot.algorithmVersion());
        assertThat(drawing.getWinnerCount()).isEqualTo(snapshot.winnerCount());
    }

    private DrawingSnapshotContract snapshotContract() {
        return DrawingSnapshotContract.from(verifiedSnapshot());
    }

    private VerifiedSnapshot verifiedSnapshot() {
        return new VerifiedSnapshot(
                2L,
                1L,
                0,
                0L,
                2,
                "WEIGHTED",
                "WEIGHTED_V1",
                "0".repeat(64),
                List.of()
        );
    }
}
