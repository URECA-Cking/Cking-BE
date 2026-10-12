package kr.co.cking.quiz.application.config;

import jakarta.validation.constraints.AssertTrue;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * YouTube URL 기반 Quiz 생성 작업 설정(이슈 #525). 영상 Grounding·Quiz 생성 Provider 설정과는 따로 둔다.
 * 오래된 작업 기준 시간과 Executor 크기는 운영 확인 전 초기값이다.
 */
@Validated
@ConfigurationProperties(prefix = "cking.quiz.generation")
public class QuizGenerationProperties {

    /** 꺼져 있으면 새 생성 요청을 받지 않는다. 기존 작업 조회는 된다. */
    private boolean enabled = false;
    /** 접수 후 이 시간이 지나도 PENDING이면 FAILED로 본다. */
    private Duration stalePending = Duration.ofMinutes(10);
    /** 시작 후 이 시간이 지나도 RUNNING이면 UNKNOWN으로 본다. */
    private Duration staleRunning = Duration.ofMinutes(15);
    private Executor executor = new Executor();

    /** 0이나 음수면 모든 작업이 곧바로 오래된 작업으로 정리되므로 기동을 막는다. */
    @AssertTrue(message = "cking.quiz.generation.stale-pending과 stale-running은 양수여야 합니다.")
    public boolean isStaleDurationsPositive() {
        return positive(stalePending) && positive(staleRunning);
    }

    private static boolean positive(Duration value) {
        return value != null && !value.isNegative() && !value.isZero();
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Duration getStalePending() {
        return stalePending;
    }

    public void setStalePending(Duration stalePending) {
        this.stalePending = stalePending;
    }

    public Duration getStaleRunning() {
        return staleRunning;
    }

    public void setStaleRunning(Duration staleRunning) {
        this.staleRunning = staleRunning;
    }

    public Executor getExecutor() {
        return executor;
    }

    public void setExecutor(Executor executor) {
        this.executor = executor;
    }

    public static class Executor {

        private int poolSize = 2;
        private int queueCapacity = 10;
        private int awaitTerminationSeconds = 30;

        public int getPoolSize() {
            return poolSize;
        }

        public void setPoolSize(int poolSize) {
            this.poolSize = poolSize;
        }

        public int getQueueCapacity() {
            return queueCapacity;
        }

        public void setQueueCapacity(int queueCapacity) {
            this.queueCapacity = queueCapacity;
        }

        public int getAwaitTerminationSeconds() {
            return awaitTerminationSeconds;
        }

        public void setAwaitTerminationSeconds(int awaitTerminationSeconds) {
            this.awaitTerminationSeconds = awaitTerminationSeconds;
        }
    }
}
