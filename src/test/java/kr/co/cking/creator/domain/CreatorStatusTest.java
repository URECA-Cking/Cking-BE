package kr.co.cking.creator.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import kr.co.cking.common.exception.BusinessException;
import org.junit.jupiter.api.Test;

class CreatorStatusTest {
    @Test
    void 정지와_복구는_활동_가능_여부와_공개_안내를_바꾼다() {
        Creator creator = new Creator(1L, "creator");
        assertThat(creator.getStatus()).isEqualTo(CreatorStatus.ACTIVE);
        assertThat(creator.canOperate()).isTrue();

        creator.changeStatus(CreatorStatus.SUSPENDED, "정지 안내");
        assertThat(creator.canOperate()).isFalse();
        assertThat(creator.getStatusNotice()).isEqualTo("정지 안내");
        assertThatThrownBy(creator::requireActive)
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", CreatorErrorCode.CREATOR_NOT_ACTIVE);

        creator.changeStatus(CreatorStatus.ACTIVE, "복구 안내");
        assertThat(creator.canOperate()).isTrue();
        assertThat(creator.getStatusNotice()).isNull();
    }

    @Test
    void 박탈은_최종_상태이고_동일_상태_변경도_거부한다() {
        Creator creator = new Creator(1L, "creator");
        creator.changeStatus(CreatorStatus.REVOKED, "박탈 안내");
        assertThat(creator.canOperate()).isFalse();
        for (CreatorStatus next : CreatorStatus.values()) {
            assertThatThrownBy(() -> creator.changeStatus(next, "안내"))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", CreatorErrorCode.INVALID_CREATOR_STATUS_TRANSITION);
        }
    }
}
