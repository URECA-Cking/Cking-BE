package kr.co.cking.post.filter;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** 댓글 필터 서비스 호출과 비동기 처리 자원 설정. 필터 서비스가 준비되기 전에는 enabled를 꺼 둔다. */
@ConfigurationProperties(prefix = "cking.comment-filter")
@Getter
@Setter
public class CommentFilterProperties {

    private boolean enabled = false;
    private String baseUrl = "http://localhost:8000";
    private Duration connectTimeout = Duration.ofSeconds(2);
    private Duration readTimeout = Duration.ofSeconds(5);
    private Executor executor = new Executor();
    private Retry retry = new Retry();

    /** 필터 호출 전용 Executor. 무제한 queue를 두지 않고, 포화로 거절된 댓글은 PENDING으로 남겨 재필터링이 처리한다. */
    @Getter
    @Setter
    public static class Executor {

        private int corePoolSize = 2;
        private int maxPoolSize = 2;
        private int queueCapacity = 100;
        private int awaitTerminationSeconds = 30;
    }

    /**
     * 판정하지 못한 댓글을 다시 제출하는 스케줄러 설정. 한 번에 제출하는 건수는 Executor 큐의 남은 자리를 넘지 않는다.
     * 시도 횟수가 {@code maxAttempts}에 닿으면 더 보내지 않고 {@code error} 로그만 남긴다.
     */
    @Getter
    @Setter
    public static class Retry {

        private boolean enabled = true;
        private long intervalMs = 60_000L;
        private Duration pendingGracePeriod = Duration.ofMinutes(1);
        private int batchSize = 20;
        private int maxAttempts = 5;
        private Duration backoffBase = Duration.ofMinutes(1);
        private Duration backoffMax = Duration.ofHours(1);

        /** 이미 재제출한 횟수에 따라 base, 2*base, 4*base 순으로 늘어나고 최대값에서 제한한다. */
        public Duration backoff(int previousAttempts) {
            int exponent = Math.min(Math.max(previousAttempts, 0), 30);
            Duration calculated;
            try {
                calculated = backoffBase.multipliedBy(1L << exponent);
            } catch (ArithmeticException exception) {
                return backoffMax;
            }
            return calculated.compareTo(backoffMax) > 0 ? backoffMax : calculated;
        }
    }
}
