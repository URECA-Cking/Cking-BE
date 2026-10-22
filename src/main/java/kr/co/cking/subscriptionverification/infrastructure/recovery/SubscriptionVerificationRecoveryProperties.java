package kr.co.cking.subscriptionverification.infrastructure.recovery;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 구독 인증 처리·보상 Recovery의 실행 한계와 backoff 설정이다. */
@ConfigurationProperties(prefix = "cking.verification.youtube-subscription.recovery")
public class SubscriptionVerificationRecoveryProperties {

    private Duration pendingGracePeriod = Duration.ofMinutes(1);
    private long intervalMs = 60_000L;
    private int batchSize = 20;
    private int maxProcessingAttempts = 3;
    private Duration rewardBackoffBase = Duration.ofMinutes(1);
    private Duration rewardBackoffMax = Duration.ofHours(1);

    public Duration getPendingGracePeriod() {
        return pendingGracePeriod;
    }

    public long getIntervalMs() {
        return intervalMs;
    }

    public void setIntervalMs(long intervalMs) {
        this.intervalMs = intervalMs;
    }

    public void setPendingGracePeriod(Duration pendingGracePeriod) {
        this.pendingGracePeriod = pendingGracePeriod;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    public int getMaxProcessingAttempts() {
        return maxProcessingAttempts;
    }

    public void setMaxProcessingAttempts(int maxProcessingAttempts) {
        this.maxProcessingAttempts = maxProcessingAttempts;
    }

    public Duration getRewardBackoffBase() {
        return rewardBackoffBase;
    }

    public void setRewardBackoffBase(Duration rewardBackoffBase) {
        this.rewardBackoffBase = rewardBackoffBase;
    }

    public Duration getRewardBackoffMax() {
        return rewardBackoffMax;
    }

    public void setRewardBackoffMax(Duration rewardBackoffMax) {
        this.rewardBackoffMax = rewardBackoffMax;
    }

    /** 실패 횟수에 따라 base, 2*base, 4*base 순으로 증가하고 최대값에서 제한한다. */
    public Duration rewardBackoff(int previousAttemptCount) {
        int exponent = Math.min(Math.max(previousAttemptCount, 0), 30);
        Duration calculated;
        try {
            calculated = rewardBackoffBase.multipliedBy(1L << exponent);
        } catch (ArithmeticException exception) {
            return rewardBackoffMax;
        }
        return calculated.compareTo(rewardBackoffMax) > 0 ? rewardBackoffMax : calculated;
    }
}
