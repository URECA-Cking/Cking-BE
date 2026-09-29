package kr.co.cking.subscriptionverification.scheduler;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import kr.co.cking.subscriptionverification.application.SubscriptionVerificationProcessingTrigger;
import kr.co.cking.subscriptionverification.application.SubscriptionVerificationRecoveryService;
import kr.co.cking.subscriptionverification.application.SubscriptionVerificationRecoveryTarget;
import kr.co.cking.subscriptionverification.application.SubscriptionVerificationRewardAttemptResult;
import kr.co.cking.subscriptionverification.application.SubscriptionVerificationRewardService;
import kr.co.cking.subscriptionverification.infrastructure.recovery.SubscriptionVerificationRecoveryProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 유실·중단된 구독 인증 처리와 승인 보상을 DB 상태 기준으로 복구한다. */
@Component
@ConditionalOnProperty(
        name = "cking.verification.youtube-subscription.recovery.enabled",
        havingValue = "true",
        matchIfMissing = true)
@Slf4j
public class SubscriptionVerificationRecoveryScheduler {

    private final SubscriptionVerificationRecoveryService recoveryService;
    private final SubscriptionVerificationProcessingTrigger processingTrigger;
    private final SubscriptionVerificationRewardService rewardService;
    private final SubscriptionVerificationRecoveryProperties properties;
    private final Clock clock;
    private final Counter processingSubmittedCounter;
    private final Counter processingExhaustedCounter;
    private final Counter rewardAcceptedCounter;
    private final Counter rewardRetryCounter;

    public SubscriptionVerificationRecoveryScheduler(
            SubscriptionVerificationRecoveryService recoveryService,
            SubscriptionVerificationProcessingTrigger processingTrigger,
            SubscriptionVerificationRewardService rewardService,
            SubscriptionVerificationRecoveryProperties properties,
            Clock clock,
            MeterRegistry meterRegistry) {
        this.recoveryService = recoveryService;
        this.processingTrigger = processingTrigger;
        this.rewardService = rewardService;
        this.properties = properties;
        this.clock = clock;
        this.processingSubmittedCounter = counter(
                meterRegistry, "subscription_verification.recovery.processing.submitted");
        this.processingExhaustedCounter = counter(
                meterRegistry, "subscription_verification.recovery.processing.exhausted");
        this.rewardAcceptedCounter = counter(
                meterRegistry, "subscription_verification.recovery.reward.accepted");
        this.rewardRetryCounter = counter(
                meterRegistry, "subscription_verification.recovery.reward.retry");
    }

    @Scheduled(
            fixedDelayString = "${cking.verification.youtube-subscription.recovery.interval-ms:60000}",
            initialDelayString = "${cking.verification.youtube-subscription.recovery.interval-ms:60000}")
    public void recover() {
        Instant now = clock.instant();
        failExhaustedProcessing(now);
        submitProcessingRecovery(now);
        recoverRewards(now);
    }

    private void failExhaustedProcessing(Instant now) {
        for (Long verificationId : recoveryService.findExhaustedProcessingIds(
                now, properties.getMaxProcessingAttempts(), properties.getBatchSize())) {
            try {
                if (recoveryService.failExhaustedProcessing(
                        verificationId, now, properties.getMaxProcessingAttempts())) {
                    processingExhaustedCounter.increment();
                    log.error("구독 인증 처리 시도 상한을 소진했습니다. verificationId={}", verificationId);
                }
            } catch (RuntimeException exception) {
                log.error("구독 인증 처리 시도 상한 종료에 실패했습니다. verificationId={}",
                        verificationId, exception);
            }
        }
    }

    private void submitProcessingRecovery(Instant now) {
        Instant pendingBefore = now.minus(properties.getPendingGracePeriod());
        for (Long verificationId : recoveryService.findProcessingCandidates(
                now,
                pendingBefore,
                properties.getMaxProcessingAttempts(),
                properties.getBatchSize())) {
            try {
                processingTrigger.trigger(verificationId);
                processingSubmittedCounter.increment();
            } catch (RuntimeException exception) {
                log.warn("구독 인증 복구 작업 제출에 실패했습니다. verificationId={}",
                        verificationId, exception);
            }
        }
    }

    private void recoverRewards(Instant now) {
        for (SubscriptionVerificationRecoveryTarget target : recoveryService.findRewardCandidates(
                now, properties.getBatchSize())) {
            try {
                SubscriptionVerificationRewardAttemptResult result =
                        rewardService.reward(target.rewardCommand());
                if (result == SubscriptionVerificationRewardAttemptResult.ACCEPTED) {
                    rewardAcceptedCounter.increment();
                    continue;
                }
                scheduleRewardRetry(target, now);
            } catch (RuntimeException exception) {
                log.warn("구독 인증 보상 복구 실행에 실패했습니다. verificationId={}",
                        target.verificationId(), exception);
                scheduleRewardRetry(target, now);
            }
        }
    }

    private void scheduleRewardRetry(
            SubscriptionVerificationRecoveryTarget target,
            Instant failedAt) {
        try {
            Duration backoff = properties.rewardBackoff(target.rewardAttemptCount());
            if (recoveryService.scheduleRewardRetry(
                    target.verificationId(),
                    target.rewardAttemptCount(),
                    failedAt,
                    failedAt.plus(backoff))) {
                rewardRetryCounter.increment();
            }
        } catch (RuntimeException exception) {
            log.error("구독 인증 보상 재시도 예약에 실패했습니다. verificationId={}",
                    target.verificationId(), exception);
        }
    }

    private Counter counter(MeterRegistry registry, String name) {
        return Counter.builder(name).register(registry);
    }
}
