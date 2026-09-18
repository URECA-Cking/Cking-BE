package kr.co.cking.drawing.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.snapshot.application.VerifiedSnapshot;
import kr.co.cking.snapshot.application.VerifiedSnapshotTestFactory;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class DrawingTest {

    @Test
    void READY_Drawing은_확정입력과_함께_RUNNING으로_전이한다() {
        Drawing drawing = Drawing.createInitial(snapshotContract(), 3L, 4L);
        Instant startedAt = Instant.parse("2026-09-20T00:00:00Z");

        drawing.start("input\n", "a".repeat(64), startedAt);

        assertThat(drawing.getStatus()).isEqualTo(DrawingStatus.RUNNING);
        assertThat(drawing.getInputPayload()).isEqualTo("input\n");
        assertThat(drawing.getInputHash()).isEqualTo("a".repeat(64));
        assertThat(drawing.getFirstStartedAt()).isEqualTo(startedAt);
        assertThat(drawing.getAttemptCount()).isEqualTo(1);
    }

    @Test
    void RUNNING_Drawing은_결과와_함께_COMPLETED로_전이한다() {
        Drawing drawing = Drawing.createInitial(snapshotContract(), 3L, 4L);
        drawing.start("input\n", "a".repeat(64), Instant.parse("2026-09-20T00:00:00Z"));
        Instant completedAt = Instant.parse("2026-09-20T00:01:00Z");

        drawing.complete("output\n", "b".repeat(64), completedAt);

        assertThat(drawing.getStatus()).isEqualTo(DrawingStatus.COMPLETED);
        assertThat(drawing.getOutputPayload()).isEqualTo("output\n");
        assertThat(drawing.getResultHash()).isEqualTo("b".repeat(64));
        assertThat(drawing.getCompletedAt()).isEqualTo(completedAt);
    }

    @Test
    void 허용되지_않은_Drawing_상태전이는_거부한다() {
        Drawing drawing = Drawing.createInitial(snapshotContract(), 3L, 4L);

        assertThatThrownBy(() -> drawing.complete("output\n", "b".repeat(64), Instant.now()))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", DrawingErrorCode.INVALID_STATE);

        drawing.start("input\n", "a".repeat(64), Instant.now());
        assertThatThrownBy(() -> drawing.start("input\n", "a".repeat(64), Instant.now()))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", DrawingErrorCode.INVALID_STATE);
    }

    @Test
    void 완료되고_비공개인_Drawing은_공개하면_PUBLIC이_되고_공개시각이_기록된다() {
        Drawing drawing = completedDrawing();
        Instant publishedAt = Instant.parse("2026-09-20T00:00:00Z");

        drawing.publish(publishedAt);

        assertThat(drawing.getVisibility()).isEqualTo(DrawingVisibility.PUBLIC);
        assertThat(drawing.getPublishedAt()).isEqualTo(publishedAt);
    }

    @Test
    void 이미_공개된_Drawing을_다시_공개해도_공개시각이_바뀌지_않는다() {
        Drawing drawing = completedDrawing();
        Instant firstPublishedAt = Instant.parse("2026-09-20T00:00:00Z");
        drawing.publish(firstPublishedAt);

        drawing.publish(Instant.parse("2026-09-21T00:00:00Z"));

        assertThat(drawing.getVisibility()).isEqualTo(DrawingVisibility.PUBLIC);
        assertThat(drawing.getPublishedAt()).isEqualTo(firstPublishedAt);
    }

    @Test
    void 완료되지_않은_Drawing은_공개할_수_없다() {
        Drawing drawing = Drawing.createInitial(snapshotContract(), 3L, 4L);

        assertThatThrownBy(() -> drawing.publish(Instant.now()))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", DrawingErrorCode.DRAWING_NOT_COMPLETED);
        assertThat(drawing.getVisibility()).isEqualTo(DrawingVisibility.PRIVATE);
    }

    private Drawing completedDrawing() {
        Drawing drawing = Drawing.createInitial(snapshotContract(), 3L, 4L);
        ReflectionTestUtils.setField(drawing, "status", DrawingStatus.COMPLETED);
        return drawing;
    }

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
        return VerifiedSnapshotTestFactory.create(
                2L, 1L,
                2,
                "WEIGHTED",
                "WEIGHTED_V1"
        );
    }
}
