package kr.co.cking.winner.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import kr.co.cking.common.exception.BusinessException;
import org.junit.jupiter.api.Test;

class WinnerManagementTest {

    /** SELECTED Winner는 포기하면 DECLINED 종결 상태로 전이한다. */
    @Test
    void SELECTED_Winner는_당첨을_포기하면_DECLINED가_된다() {
        WinnerManagement management = WinnerManagement.selected(1L);

        management.decline();

        assertThat(management.getStatus()).isEqualTo(WinnerManagementStatus.DECLINED);
    }

    /** DECLINED 종결 상태에서는 포기 상태 전이를 반복할 수 없다. */
    @Test
    void DECLINED_종결_상태에서는_당첨_포기를_다시_수행할_수_없다() {
        WinnerManagement management = WinnerManagement.selected(1L);
        management.decline();

        assertThatThrownBy(management::decline)
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", WinnerErrorCode.INVALID_STATE);
    }

    /** SELECTED Winner는 관리자가 수령 완료하면 RECEIVED 종결 상태로 전이한다. */
    @Test
    void SELECTED_Winner는_수령_완료하면_RECEIVED가_된다() {
        WinnerManagement management = WinnerManagement.selected(1L);

        management.receive();

        assertThat(management.getStatus()).isEqualTo(WinnerManagementStatus.RECEIVED);
    }

    /** RECEIVED 종결 상태에서는 수령 완료 상태 전이를 반복할 수 없다. */
    @Test
    void RECEIVED_종결_상태에서는_수령_완료를_다시_수행할_수_없다() {
        WinnerManagement management = WinnerManagement.selected(1L);
        management.receive();

        assertThatThrownBy(management::receive)
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", WinnerErrorCode.INVALID_STATE);
    }

    /** SELECTED Winner는 관리자가 자격 박탈하면 DISQUALIFIED 종결 상태로 전이한다. */
    @Test
    void SELECTED_Winner는_자격_박탈하면_DISQUALIFIED가_된다() {
        WinnerManagement management = WinnerManagement.selected(1L);

        management.disqualify();

        assertThat(management.getStatus()).isEqualTo(WinnerManagementStatus.DISQUALIFIED);
    }

    /** DISQUALIFIED 종결 상태에서는 어떤 상태 변경도 다시 수행할 수 없다. */
    @Test
    void DISQUALIFIED_종결_상태에서는_자격_박탈을_다시_수행할_수_없다() {
        WinnerManagement management = WinnerManagement.selected(1L);
        management.disqualify();

        assertThatThrownBy(management::disqualify)
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", WinnerErrorCode.INVALID_STATE);
    }
}
