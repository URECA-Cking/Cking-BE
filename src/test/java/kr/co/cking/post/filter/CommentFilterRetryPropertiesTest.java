package kr.co.cking.post.filter;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class CommentFilterRetryPropertiesTest {

    private final CommentFilterProperties.Retry retry = new CommentFilterProperties().getRetry();

    @Test
    void 백오프는_횟수마다_두_배로_늘고_최대값에서_멈춘다() {
        assertThat(retry.backoff(0)).isEqualTo(Duration.ofMinutes(1));
        assertThat(retry.backoff(1)).isEqualTo(Duration.ofMinutes(2));
        assertThat(retry.backoff(3)).isEqualTo(Duration.ofMinutes(8));
        assertThat(retry.backoff(6)).isEqualTo(Duration.ofHours(1));
        assertThat(retry.backoff(100)).isEqualTo(Duration.ofHours(1));
        assertThat(retry.backoff(-1)).isEqualTo(Duration.ofMinutes(1));
    }

    @Test
    void Dispatcher는_큐의_남은_자리를_알려준다() throws Exception {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(3);
        executor.initialize();
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(1);
        CommentFilterDispatcher dispatcher = new CommentFilterDispatcher(executor, new CommentFilterWorker(null, null) {
            @Override
            public void process(Long commentId) {
                started.countDown();
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        try {
            assertThat(dispatcher.remainingQueueCapacity()).isEqualTo(3);

            dispatcher.dispatch(1L);   // 유일한 스레드가 잡는다
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            dispatcher.dispatch(2L);   // 큐에 들어간다
            dispatcher.dispatch(3L);

            assertThat(dispatcher.remainingQueueCapacity()).isEqualTo(1);
        } finally {
            release.countDown();
            executor.shutdown();
        }
    }
}
