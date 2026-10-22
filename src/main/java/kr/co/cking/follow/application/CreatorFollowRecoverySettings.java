package kr.co.cking.follow.application;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public record CreatorFollowRecoverySettings(Duration initialDelay) {
    public CreatorFollowRecoverySettings(
            @Value("${cking.recommendation.tracking.recovery-initial-delay:PT2M}") Duration initialDelay) {
        if (initialDelay == null || initialDelay.isNegative() || initialDelay.isZero() || initialDelay.getNano() != 0) {
            throw new IllegalArgumentException("Follow recovery initial delay must be positive whole seconds");
        }
        this.initialDelay = initialDelay;
    }
}
