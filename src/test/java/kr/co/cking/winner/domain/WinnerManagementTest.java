package kr.co.cking.winner.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import kr.co.cking.common.exception.BusinessException;
import org.junit.jupiter.api.Test;

class WinnerManagementTest {

    @Test
    void SELECTED_Winner는_당첨을_포기하면_DECLINED가_된다() {
        WinnerManagement management = WinnerManagement.selected(1L);

        management.decline();

        assertThat(management.getStatus()).isEqualTo(WinnerManagementStatus.DECLINED);
    }

    @Test
    void DECLINED_종결_상태에서는_당첨_포기를_다시_수행할_수_없다() {
        WinnerManagement management = WinnerManagement.selected(1L);
        management.decline();

        assertThatThrownBy(management::decline)
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", WinnerErrorCode.INVALID_STATE);
    }
}
