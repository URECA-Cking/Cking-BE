package kr.co.cking.creator.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import kr.co.cking.common.exception.BusinessException;
import org.junit.jupiter.api.Test;

class CreatorActivityNameTest {

    @Test
    void Member_닉네임과_같은_정규화와_문자_규칙을_공유한다() {
        CreatorActivityName activityName = CreatorActivityName.of("Cafe-가");

        assertThat(activityName.value()).isEqualTo("Cafe-가");
        assertThat(activityName.normalized()).isEqualTo("cafe-가");
        assertThatThrownBy(() -> CreatorActivityName.of("ㅂㅅ")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 최초_설정은_변경_시계를_시작하지_않고_실제_변경부터_14일_제한을_시작한다() {
        Creator creator = new Creator(1L, "OAuth 이름");
        LocalDateTime now = LocalDateTime.of(2026, 10, 12, 0, 0);

        creator.setInitialActivityName(CreatorActivityName.of("first"));
        assertThat(creator.getActivityNameChangedAt()).isNull();
        assertThat(creator.canChangeActivityNameAt(now)).isTrue();

        creator.changeActivityName(CreatorActivityName.of("second"), now);
        assertThat(creator.activityNameChangeableAt()).isEqualTo(now.plusDays(14));
        assertThat(creator.canChangeActivityNameAt(now.plusDays(14).minusNanos(1))).isFalse();
    }

    @Test
    void 복귀는_현재와_이전_활동명을_교체하고_변경시각을_유지한다() {
        Creator creator = new Creator(1L, "OAuth 이름");
        LocalDateTime changedAt = LocalDateTime.of(2026, 10, 12, 0, 0);
        creator.setInitialActivityName(CreatorActivityName.of("first"));
        creator.changeActivityName(CreatorActivityName.of("second"), changedAt);

        creator.revertActivityName();

        assertThat(creator.getActivityName()).isEqualTo("first");
        assertThat(creator.getPreviousActivityName()).isEqualTo("second");
        assertThat(creator.getActivityNameChangedAt()).isEqualTo(changedAt);
    }

    @Test
    void 기존_이관_Creator는_활동명을_설정하기_전_작성_권한이_없다() {
        Creator creator = new Creator(1L, "OAuth 이름");
        org.springframework.test.util.ReflectionTestUtils.setField(creator, "activityNameConfigured", false);

        assertThatThrownBy(creator::requireActivityNameConfigured)
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(CreatorErrorCode.ACTIVITY_NAME_SETUP_REQUIRED);
    }
}
