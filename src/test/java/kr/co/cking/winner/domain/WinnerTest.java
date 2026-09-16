package kr.co.cking.winner.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
    void WinnerManagement는_SELECTED로_생성된다() {
        WinnerManagement management = WinnerManagement.selected(1L);

        assertThat(management.getWinnerId()).isEqualTo(1L);
        assertThat(management.getStatus()).isEqualTo(WinnerManagementStatus.SELECTED);
    }
}
