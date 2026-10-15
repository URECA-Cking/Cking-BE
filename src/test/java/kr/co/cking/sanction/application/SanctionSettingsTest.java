package kr.co.cking.sanction.application;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SanctionSettingsTest {

    private static final Duration DAYS_90 = Duration.ofDays(90);

    @Test
    void 경고_횟수별_제한_기간은_단계를_따르고_마지막_값이_그_이상에_계속_쓰인다() {
        SanctionSettings settings = new SanctionSettings(
                DAYS_90, List.of(Duration.ofDays(1), Duration.ofDays(3), Duration.ofDays(7)));

        assertThat(settings.commentRestrictionFor(1)).isEqualTo(Duration.ofDays(1));
        assertThat(settings.commentRestrictionFor(2)).isEqualTo(Duration.ofDays(3));
        assertThat(settings.commentRestrictionFor(3)).isEqualTo(Duration.ofDays(7));
        assertThat(settings.commentRestrictionFor(4)).isEqualTo(Duration.ofDays(7));
        assertThat(settings.commentRestrictionFor(1_000)).isEqualTo(Duration.ofDays(7));
    }

    @Test
    void 설정_문자열은_쉼표로_나눠_공백을_무시하고_읽는다() {
        SanctionSettings settings = new SanctionSettings(DAYS_90, "P1D, P3D ,P7D");

        assertThat(settings.commentRestrictionSteps())
                .containsExactly(Duration.ofDays(1), Duration.ofDays(3), Duration.ofDays(7));
    }

    @Test
    void 경고_횟수가_1_미만이면_거부한다() {
        SanctionSettings settings = new SanctionSettings(DAYS_90, "P1D");

        assertThatThrownBy(() -> settings.commentRestrictionFor(0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 잘못된_설정은_기동_시점에_거부한다() {
        assertThatThrownBy(() -> new SanctionSettings(Duration.ZERO, "P1D")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SanctionSettings(Duration.ofDays(-1), "P1D")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SanctionSettings(DAYS_90, "")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SanctionSettings(DAYS_90, "P1D,PT0S")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SanctionSettings(DAYS_90, "P1D,-P1D")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SanctionSettings(DAYS_90, "P1D,abc")).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> new SanctionSettings(DAYS_90, List.of())).isInstanceOf(IllegalArgumentException.class);
    }
}
