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

    /** 필터 호출 전용 Executor. 무제한 queue를 두지 않고, 포화로 거절된 댓글은 PENDING으로 남겨 재필터링이 처리한다. */
    @Getter
    @Setter
    public static class Executor {

        private int corePoolSize = 2;
        private int maxPoolSize = 2;
        private int queueCapacity = 100;
        private int awaitTerminationSeconds = 30;
    }
}
