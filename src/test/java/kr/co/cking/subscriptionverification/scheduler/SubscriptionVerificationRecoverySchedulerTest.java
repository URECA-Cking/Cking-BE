package kr.co.cking.subscriptionverification.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import kr.co.cking.subscriptionverification.application.SubscriptionVerificationProcessingTrigger;
import kr.co.cking.subscriptionverification.application.SubscriptionVerificationRecoveryService;
import kr.co.cking.subscriptionverification.application.SubscriptionVerificationRecoveryTarget;
import kr.co.cking.subscriptionverification.application.SubscriptionVerificationRewardAttemptResult;
import kr.co.cking.subscriptionverification.application.SubscriptionVerificationRewardCommand;
import kr.co.cking.subscriptionverification.application.SubscriptionVerificationRewardService;
import kr.co.cking.subscriptionverification.infrastructure.recovery.SubscriptionVerificationRecoveryProperties;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class SubscriptionVerificationRecoverySchedulerTest {

    private static final Instant NOW = Instant.parse("2026-09-29T12:00:00Z");

    private final SubscriptionVerificationRecoveryService recoveryService =
            Mockito.mock(SubscriptionVerificationRecoveryService.class);
    private final SubscriptionVerificationProcessingTrigger processingTrigger =
            Mockito.mock(SubscriptionVerificationProcessingTrigger.class);
    private final SubscriptionVerificationRewardService rewardService =
            Mockito.mock(SubscriptionVerificationRewardService.class);
    private final SubscriptionVerificationRecoveryProperties properties =
            new SubscriptionVerificationRecoveryProperties();
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final SubscriptionVerificationRecoveryScheduler scheduler =
            new SubscriptionVerificationRecoveryScheduler(
                    recoveryService,
                    processingTrigger,
                    rewardService,
                    properties,
                    Clock.fixed(NOW, ZoneOffset.UTC),
                    meterRegistry);

    @Test
    void 보상_backoff는_지수로_증가하고_최대_한_시간으로_제한한다() {
        assertThat(properties.rewardBackoff(0)).isEqualTo(Duration.ofMinutes(1));
        assertThat(properties.rewardBackoff(2)).isEqualTo(Duration.ofMinutes(4));
        assertThat(properties.rewardBackoff(20)).isEqualTo(Duration.ofHours(1));
    }

    @Test
    void 처리_상한_종료와_복구_제출을_각_건별로_격리한다() {
        given(recoveryService.findExhaustedProcessingIds(NOW, 3, 20))
                .willReturn(List.of(1L, 2L));
        willThrow(new IllegalStateException("db unavailable"))
                .given(recoveryService).failExhaustedProcessing(1L, NOW, 3);
        given(recoveryService.failExhaustedProcessing(2L, NOW, 3)).willReturn(true);
        given(recoveryService.findProcessingCandidates(
                NOW, NOW.minusSeconds(60), 3, 20))
                .willReturn(List.of(3L, 4L));
        willThrow(new IllegalStateException("trigger unavailable"))
                .given(processingTrigger).trigger(3L);
        given(recoveryService.findRewardCandidates(NOW, 20)).willReturn(List.of());

        scheduler.recover();

        then(processingTrigger).should().trigger(3L);
        then(processingTrigger).should().trigger(4L);
        assertThat(counter("subscription_verification.recovery.processing.exhausted"))
                .isEqualTo(1.0);
        assertThat(counter("subscription_verification.recovery.processing.submitted"))
                .isEqualTo(1.0);
    }

    @Test
    void 승인_보상은_수락으로_종료하거나_지수_backoff로_재예약한다() {
        SubscriptionVerificationRecoveryTarget accepted = rewardTarget(10L, 0);
        SubscriptionVerificationRecoveryTarget retry = rewardTarget(11L, 2);
        given(recoveryService.findExhaustedProcessingIds(NOW, 3, 20)).willReturn(List.of());
        given(recoveryService.findProcessingCandidates(
                NOW, NOW.minusSeconds(60), 3, 20)).willReturn(List.of());
        given(recoveryService.findRewardCandidates(NOW, 20))
                .willReturn(List.of(accepted, retry));
        given(rewardService.reward(accepted.rewardCommand()))
                .willReturn(SubscriptionVerificationRewardAttemptResult.ACCEPTED);
        given(rewardService.reward(retry.rewardCommand()))
                .willReturn(SubscriptionVerificationRewardAttemptResult.RETRY_REQUIRED);
        given(recoveryService.scheduleRewardRetry(
                11L, 2, NOW, NOW.plusSeconds(240))).willReturn(true);

        scheduler.recover();

        then(recoveryService).should().scheduleRewardRetry(
                11L, 2, NOW, NOW.plusSeconds(240));
        assertThat(counter("subscription_verification.recovery.reward.accepted"))
                .isEqualTo(1.0);
        assertThat(counter("subscription_verification.recovery.reward.retry"))
                .isEqualTo(1.0);
    }

    @Test
    void 보상_호출_예외도_다른_대상을_중단하지_않고_재예약한다() {
        SubscriptionVerificationRecoveryTarget failed = rewardTarget(20L, 0);
        SubscriptionVerificationRecoveryTarget accepted = rewardTarget(21L, 0);
        given(recoveryService.findExhaustedProcessingIds(NOW, 3, 20)).willReturn(List.of());
        given(recoveryService.findProcessingCandidates(
                NOW, NOW.minusSeconds(60), 3, 20)).willReturn(List.of());
        given(recoveryService.findRewardCandidates(NOW, 20))
                .willReturn(List.of(failed, accepted));
        willThrow(new IllegalStateException("invalid frozen input"))
                .given(rewardService).reward(failed.rewardCommand());
        given(rewardService.reward(accepted.rewardCommand()))
                .willReturn(SubscriptionVerificationRewardAttemptResult.ACCEPTED);

        scheduler.recover();

        then(recoveryService).should().scheduleRewardRetry(
                20L, 0, NOW, NOW.plusSeconds(60));
        then(rewardService).should().reward(accepted.rewardCommand());
    }

    private SubscriptionVerificationRecoveryTarget rewardTarget(Long verificationId, int attemptCount) {
        return SubscriptionVerificationRecoveryTarget.reward(
                new SubscriptionVerificationRewardCommand(
                        verificationId,
                        7L,
                        42L,
                        103L,
                        "10000000-0000-4000-8000-00000000000" + verificationId % 10,
                        "2026-09-29"),
                attemptCount);
    }

    private double counter(String name) {
        return meterRegistry.get(name).counter().count();
    }
}
