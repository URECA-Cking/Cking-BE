package kr.co.cking.subscriptionverification.application;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskRejectedException;

class SubscriptionVerificationProcessingExecutorTriggerTest {

    /** queue 거절은 HTTP 경계까지 예외를 전파하지 않고 metric만 증가시키는지 검증한다. */
    @Test
    void queue_거절은_예외를_전파하지_않고_metric을_기록한다() {
        SubscriptionVerificationProcessingWorker worker = mock(SubscriptionVerificationProcessingWorker.class);
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        SubscriptionVerificationProcessingExecutorTrigger trigger =
                new SubscriptionVerificationProcessingExecutorTrigger(worker, meterRegistry);
        doThrow(new TaskRejectedException("queue full")).when(worker).process(123L);

        assertThatCode(() -> trigger.trigger(123L)).doesNotThrowAnyException();

        then(worker).should().process(123L);
        org.assertj.core.api.Assertions.assertThat(meterRegistry
                .get("subscription_verification.executor.rejected")
                .counter()
                .count()).isEqualTo(1.0);
    }
}
