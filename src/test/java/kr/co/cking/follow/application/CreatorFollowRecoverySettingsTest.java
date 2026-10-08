package kr.co.cking.follow.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class CreatorFollowRecoverySettingsTest {
    @Test
    void 양수_정수초_유예를_허용한다() {
        assertThat(new CreatorFollowRecoverySettings(Duration.ofMinutes(2)).initialDelay()).isEqualTo(Duration.ofSeconds(120));
    }

    @Test
    void null과_0_음수_소수초_유예를_거부한다() {
        assertThatThrownBy(() -> new CreatorFollowRecoverySettings(null)).isInstanceOf(IllegalArgumentException.class);
        for (var delay : new Duration[] {Duration.ZERO, Duration.ofSeconds(-1), Duration.ofMillis(500)}) {
            assertThatThrownBy(() -> new CreatorFollowRecoverySettings(delay)).isInstanceOf(IllegalArgumentException.class);
        }
    }
}
