package kr.co.cking.post.filter;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.client.RestClient;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 댓글 필터 연동 Bean. {@code cking.comment-filter.enabled=true}일 때만 만든다. 꺼져 있으면 Dispatcher가 없어
 * 댓글은 판정 없이 PENDING으로 남고, 필터 서비스가 없는 환경(로컬·테스트)에서도 애플리케이션이 기동된다.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CommentFilterProperties.class)
@ConditionalOnProperty(prefix = "cking.comment-filter", name = "enabled", havingValue = "true")
public class CommentFilterConfiguration {

    @Bean
    CommentFilterClient commentFilterClient(CommentFilterProperties properties) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(positive(properties.getConnectTimeout(), Duration.ofSeconds(2)));
        requestFactory.setReadTimeout(positive(properties.getReadTimeout(), Duration.ofSeconds(30)));
        RestClient.Builder builder = RestClient.builder()
                .baseUrl(properties.getBaseUrl())
                .requestFactory(requestFactory);
        if (properties.getAuth() == CommentFilterProperties.Auth.AWS_IAM) {
            builder.requestInterceptor(sigV4Interceptor(properties));
        }
        return new RestCommentFilterClient(builder.build());
    }

    private static AwsSigV4RequestInterceptor sigV4Interceptor(CommentFilterProperties properties) {
        if (properties.getBaseUrl() == null || !properties.getBaseUrl().startsWith("https://")) {
            throw new IllegalArgumentException("IAM 서명으로 부르는 필터 서비스 주소는 https여야 합니다.");
        }
        if (properties.getSigningRegion() == null || properties.getSigningRegion().isBlank()) {
            throw new IllegalArgumentException("IAM 서명 리전이 비어 있습니다.");
        }
        return new AwsSigV4RequestInterceptor(
                DefaultCredentialsProvider.builder().build(), properties.getSigningRegion(), Clock.systemUTC());
    }

    /** 다른 비동기 작업과 자원을 나누고, bounded queue와 AbortPolicy로 포화 시 제출을 거절한다. */
    @Bean(name = "commentFilterExecutor")
    ThreadPoolTaskExecutor commentFilterExecutor(CommentFilterProperties properties) {
        CommentFilterProperties.Executor settings = properties.getExecutor();
        if (settings.getCorePoolSize() <= 0 || settings.getMaxPoolSize() < settings.getCorePoolSize()) {
            throw new IllegalArgumentException("댓글 필터 Executor pool size 설정이 올바르지 않습니다.");
        }
        if (settings.getQueueCapacity() <= 0) {
            throw new IllegalArgumentException("댓글 필터 Executor queueCapacity는 양수여야 합니다.");
        }
        if (settings.getAwaitTerminationSeconds() <= 0) {
            throw new IllegalArgumentException("댓글 필터 Executor 종료 대기 시간은 양수여야 합니다.");
        }

        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(settings.getCorePoolSize());
        executor.setMaxPoolSize(settings.getMaxPoolSize());
        executor.setQueueCapacity(settings.getQueueCapacity());
        executor.setThreadNamePrefix("comment-filter-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(settings.getAwaitTerminationSeconds());
        return executor;
    }

    @Bean
    CommentFilterWorker commentFilterWorker(
            CommentFilterClient client, CommentFilterResultService resultService) {
        return new CommentFilterWorker(client, resultService);
    }

    @Bean
    CommentFilterDispatcher commentFilterDispatcher(
            @Qualifier("commentFilterExecutor") ThreadPoolTaskExecutor executor, CommentFilterWorker worker) {
        return new CommentFilterDispatcher(executor, worker);
    }

    private static Duration positive(Duration value, Duration fallback) {
        return value == null || value.isNegative() || value.isZero() ? fallback : value;
    }
}
