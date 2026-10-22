package kr.co.cking.winner.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import kr.co.cking.snapshot.domain.PrizeValue;
import org.junit.jupiter.api.Test;

class WinnerTest {

    @Test
    void Winner를_생성한다() {
        Winner winner = Winner.create(1L, 2L, 3L, 1, 10L);

        assertThat(winner.getEventId()).isEqualTo(1L);
        assertThat(winner.getDrawingId()).isEqualTo(2L);
        assertThat(winner.getMemberId()).isEqualTo(3L);
        assertThat(winner.getRankInDrawing()).isEqualTo(1);
        assertThat(winner.getAppliedTicketCount()).isEqualTo(10L);
    }

    @Test
    void 순위와_적용응모권수는_양수여야_한다() {
        assertThatThrownBy(() -> Winner.create(1L, 2L, 3L, 0, 10L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Winner.create(1L, 2L, 3L, 1, 0L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 상품이_배정된_Winner는_Snapshot_계보를_함께_보존한다() {
        PrizeValue prize = new PrizeValue(6L, "FIRST", "1등 상품", 1, 5L, 1);

        Winner winner = Winner.create(1L, 2L, 3L, 1, 10L, 4L, prize);

        assertThat(winner.getSnapshotId()).isEqualTo(4L);
        assertThat(winner.getSnapshotPrizeId()).isEqualTo(6L);
        assertThat(winner.getPrizeKey()).isEqualTo("FIRST");
    }

    @Test
    void 상품_Winner는_Snapshot_계보가_필수다() {
        PrizeValue prize = new PrizeValue(6L, "FIRST", "1등 상품", 1, 5L, 1);

        assertThatThrownBy(() -> Winner.create(1L, 2L, 3L, 1, 10L, null, prize))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void WinnerManagement는_SELECTED로_생성된다() {
        WinnerManagement management = WinnerManagement.selected(1L);

        assertThat(management.getWinnerId()).isEqualTo(1L);
        assertThat(management.getStatus()).isEqualTo(WinnerManagementStatus.SELECTED);
    }
}
