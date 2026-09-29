package kr.co.cking.subscriptionverification.infrastructure.async;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

class SubscriptionVerificationProcessingExecutorConfigurationTest {

    /** bounded queue가 포화 전까지 작업을 순서대로 실행하는지 검증한다. */
    @Test
    void queue에_들어간_작업은_실행된다() throws Exception {
        ThreadPoolTaskExecutor executor = executor(1, 1, 1, 1);
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch queuedExecuted = new CountDownLatch(1);
        try {
            executor.execute(() -> {
                firstStarted.countDown();
                await(releaseFirst);
            });
            assertThat(firstStarted.await(2, TimeUnit.SECONDS)).isTrue();

            executor.execute(queuedExecuted::countDown);
            releaseFirst.countDown();

            assertThat(queuedExecuted.await(2, TimeUnit.SECONDS)).isTrue();
        } finally {
            executor.destroy();
        }
    }

    /** queue가 가득 차면 CallerRunsPolicy 대신 제출 작업을 거절하는지 검증한다. */
    @Test
    void queue가_포화되면_작업을_거절한다() throws Exception {
        ThreadPoolTaskExecutor executor = executor(1, 1, 1, 1);
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        try {
            executor.execute(() -> {
                firstStarted.countDown();
                await(releaseFirst);
            });
            assertThat(firstStarted.await(2, TimeUnit.SECONDS)).isTrue();
            executor.execute(() -> {
            });

            assertThatThrownBy(() -> executor.execute(() -> {
            })).isInstanceOf(TaskRejectedException.class);
        } finally {
            releaseFirst.countDown();
            executor.destroy();
        }
    }

    /** Executor와 Provider 동시 호출 설정이 다르면 시작을 막는지 검증한다. */
    @Test
    void Provider_동시_호출_수와_maxPoolSize가_다르면_구성을_거부한다() {
        SubscriptionVerificationProcessingExecutorProperties properties = properties(1, 2, 1, 1);

        assertThatThrownBy(() -> new SubscriptionVerificationProcessingExecutorConfiguration()
                .subscriptionVerificationExecutor(properties))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Provider 동시 호출 수와 Executor maxPoolSize는 같아야 합니다.");
    }

    /** 테스트용 전용 Executor를 초기화해 반환한다. */
    private ThreadPoolTaskExecutor executor(
            int corePoolSize,
            int maxPoolSize,
            int queueCapacity,
            int providerMaxConcurrentCalls) {
        ThreadPoolTaskExecutor executor = new SubscriptionVerificationProcessingExecutorConfiguration()
                .subscriptionVerificationExecutor(properties(
                        corePoolSize, maxPoolSize, queueCapacity, providerMaxConcurrentCalls));
        executor.initialize();
        return executor;
    }

    /** 테스트에 필요한 Executor 설정값을 만든다. */
    private SubscriptionVerificationProcessingExecutorProperties properties(
            int corePoolSize,
            int maxPoolSize,
            int queueCapacity,
            int providerMaxConcurrentCalls) {
        SubscriptionVerificationProcessingExecutorProperties properties =
                new SubscriptionVerificationProcessingExecutorProperties();
        properties.setCorePoolSize(corePoolSize);
        properties.setMaxPoolSize(maxPoolSize);
        properties.setQueueCapacity(queueCapacity);
        properties.setProviderMaxConcurrentCalls(providerMaxConcurrentCalls);
        return properties;
    }

    /** latch 대기 중 인터럽트되면 테스트를 실패시킨다. */
    private static void await(CountDownLatch latch) {
        try {
            latch.await(2, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        }
    }
}
