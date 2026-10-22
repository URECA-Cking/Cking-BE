package kr.co.cking.quiz.application.config;

import jakarta.validation.constraints.AssertTrue;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Quiz Judge 평가 요청 설정(이슈 #529). Provider 설정({@code OpenAiJudgeProperties})과 같은 {@code cking.quiz.judge}
 * prefix를 쓰지만 평가 Job에 관한 값만 둔다. 기준 시간은 운영 확인 전 초기값이다.
 */
@Validated
@ConfigurationProperties(prefix = "cking.quiz.judge")
public class QuizJudgeEvaluationProperties {

    /** 꺼져 있으면 새 평가와 새 시도를 받지 않는다. 기존 Job 조회·같은 requestId 재요청은 된다. */
    private boolean enabled = false;
    /** 접수 후 이 시간이 지나도 PENDING이면 FAILED로 본다. */
    private Duration stalePending = Duration.ofMinutes(15);
    /** 시작 후 이 시간이 지나도 RUNNING이면 UNKNOWN으로 본다. 전체 실행 상한보다 길어야 한다. */
    private Duration staleRunning = Duration.ofMinutes(5);
    /** Quiz 생성과 분리한 Judge 전용 Executor. 운영 확인 전 초기값이다. */
    private Executor executor = new Executor();

    /** 0이나 음수면 모든 Job이 곧바로 오래된 Job으로 정리되므로 기동을 막는다. */
    @AssertTrue(message = "cking.quiz.judge.stale-pending과 stale-running은 양수여야 합니다.")
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

        private int poolSize = 1;
        private int queueCapacity = 4;
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
