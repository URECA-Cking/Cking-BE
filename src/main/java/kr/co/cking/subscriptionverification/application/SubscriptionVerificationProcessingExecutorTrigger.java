package kr.co.cking.subscriptionverification.application;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.stereotype.Component;

/** AFTER_COMMIT 이벤트를 전용 Executor Worker에 전달하고 queue 거절을 격리한다. */
@Component
@Slf4j
class SubscriptionVerificationProcessingExecutorTrigger
        implements SubscriptionVerificationProcessingTrigger {

    private final SubscriptionVerificationProcessingWorker worker;
    private final Counter rejectedCounter;

    /** queue 거절 metric과 실제 비동기 Worker를 조립한다. */
    SubscriptionVerificationProcessingExecutorTrigger(
            SubscriptionVerificationProcessingWorker worker,
            MeterRegistry meterRegistry) {
        this.worker = worker;
        this.rejectedCounter = Counter.builder("subscription_verification.executor.rejected")
                .description("구독 인증 전용 Executor가 queue 포화로 거절한 작업 수")
                .register(meterRegistry);
    }

    /** verificationId만 비동기 Worker에 제출하고 queue 포화는 Recovery가 복구하도록 남긴다. */
    @Override
    public void trigger(Long verificationId) {
        try {
            worker.process(verificationId);
        } catch (TaskRejectedException exception) {
            rejectedCounter.increment();
            log.warn(
                    "구독 인증 Executor queue가 포화되어 작업 제출을 거절했습니다. "
                            + "Verification은 PENDING으로 유지하며 Recovery가 재시도합니다. verificationId={}",
                    verificationId);
        }
    }
}
