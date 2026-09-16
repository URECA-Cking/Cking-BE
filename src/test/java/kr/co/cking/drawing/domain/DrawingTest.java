package kr.co.cking.drawing.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class DrawingTest {

    @Test
    void INITIAL_Drawing은_READY_PRIVATE_상태로_생성된다() {
        Drawing drawing = Drawing.createInitial(
                1L,
                2L,
                3L,
                "WEIGHTED",
                "WEIGHTED_V1",
                2,
                4L
        );

        assertThat(drawing.getEventId()).isEqualTo(1L);
        assertThat(drawing.getDrawNo()).isZero();
        assertThat(drawing.getDrawType()).isEqualTo(DrawingType.INITIAL);
        assertThat(drawing.getOriginalDrawingId()).isNull();
        assertThat(drawing.getRedrawRequestId()).isNull();
        assertThat(drawing.getSnapshotId()).isEqualTo(2L);
        assertThat(drawing.getSeedId()).isEqualTo(3L);
        assertThat(drawing.getStatus()).isEqualTo(DrawingStatus.READY);
        assertThat(drawing.getVisibility()).isEqualTo(DrawingVisibility.PRIVATE);
        assertThat(drawing.getAttemptCount()).isZero();
        assertThat(drawing.getRequestedBy()).isEqualTo(4L);
    }

    @Test
    void 필수_식별자는_양수여야_한다() {
        assertThatThrownBy(() -> Drawing.createInitial(
                0L,
                2L,
                3L,
                "WEIGHTED",
                "WEIGHTED_V1",
                1,
                4L
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 추첨방식과_알고리즘버전은_필수다() {
        assertThatThrownBy(() -> Drawing.createInitial(
                1L,
                2L,
                3L,
                " ",
                "WEIGHTED_V1",
                1,
                4L
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 당첨자수는_양수여야_한다() {
        assertThatThrownBy(() -> Drawing.createInitial(
                1L,
                2L,
                3L,
                "WEIGHTED",
                "WEIGHTED_V1",
                0,
                4L
        )).isInstanceOf(IllegalArgumentException.class);
    }
}
