package kr.co.cking.winner.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import org.junit.jupiter.api.Test;

class WinnerStatusHistoryTest {

    /** 시스템 처리처럼 변경 주체가 없는 이력도 nullable changedBy 정책으로 만들 수 있다. */
    @Test
    void 변경_주체와_사유가_없는_상태_이력을_만들_수_있다() {
        WinnerStatusHistory history = WinnerStatusHistory.create(
                1L, WinnerManagementStatus.SELECTED, WinnerManagementStatus.DECLINED, null, null
        );

        assertThat(history.getWinnerManagementId()).isEqualTo(1L);
        assertThat(history.getPreviousStatus()).isEqualTo(WinnerManagementStatus.SELECTED);
        assertThat(history.getStatus()).isEqualTo(WinnerManagementStatus.DECLINED);
        assertThat(history.getReason()).isNull();
        assertThat(history.getChangedBy()).isNull();
    }

    /** 상태 이력은 상태값 없이 생성할 수 없다. */
    @Test
    void status가_null이면_상태_이력을_만들_수_없다() {
        assertThatNullPointerException().isThrownBy(
                () -> WinnerStatusHistory.create(1L, WinnerManagementStatus.SELECTED, null, null)
        )
                .withMessage("status");
    }

    /** 변경 주체가 전달되면 식별자 양수 규칙을 지켜야 한다. */
    @Test
    void 변경_주체가_0_이하면_상태_이력을_만들_수_없다() {
        assertThatIllegalArgumentException().isThrownBy(
                () -> WinnerStatusHistory.create(
                        1L, WinnerManagementStatus.SELECTED, WinnerManagementStatus.DECLINED, "사유", 0L
                )
        ).withMessage("changedBy는 양수여야 합니다.");
    }

    /** DB reason 컬럼 최대 길이인 500자는 상태 이력 사유로 저장할 수 있다. */
    @Test
    void 사유가_500자이면_상태_이력을_만들_수_있다() {
        String reason = "가".repeat(500);

        WinnerStatusHistory history = WinnerStatusHistory.create(
                1L, WinnerManagementStatus.SELECTED, WinnerManagementStatus.DECLINED, reason, 1L
        );

        assertThat(history.getReason()).isEqualTo(reason);
    }

    /** DB reason 컬럼 길이를 넘는 사유는 저장 전에 거절한다. */
    @Test
    void 사유가_501자이면_상태_이력을_만들_수_없다() {
        assertThatIllegalArgumentException().isThrownBy(
                () -> WinnerStatusHistory.create(
                        1L, WinnerManagementStatus.SELECTED, WinnerManagementStatus.DECLINED, "가".repeat(501), 1L
                )
        ).withMessage("reason은 500자 이하여야 합니다.");
    }
}
