package kr.co.cking.subscriptionverification.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import kr.co.cking.common.storage.ObjectStorage;
import kr.co.cking.subscriptionverification.application.vision.VisionAnalysisException;
import kr.co.cking.subscriptionverification.application.vision.VisionAnalysisFailureType;
import kr.co.cking.subscriptionverification.application.vision.VisionAnalysisPort;
import kr.co.cking.subscriptionverification.application.vision.VisionAnalysisResult;
import kr.co.cking.subscriptionverification.application.vision.VisionPlatform;
import kr.co.cking.subscriptionverification.application.vision.VisionSubscriptionState;
import kr.co.cking.subscriptionverification.infrastructure.async.SubscriptionVerificationProcessingExecutorProperties;
import org.junit.jupiter.api.Test;

class SubscriptionVerificationProcessingWorkerTest {

    /** Claim을 얻지 못한 중복 작업은 Object와 VLM을 호출하지 않는지 검증한다. */
    @Test
    void Claim에_실패한_중복_작업은_VLM을_호출하지_않는다() {
        SubscriptionVerificationProcessingClaimService claimService =
                mock(SubscriptionVerificationProcessingClaimService.class);
        SubscriptionVerificationProcessingCompletionService completionService =
                mock(SubscriptionVerificationProcessingCompletionService.class);
        SubscriptionVerificationRewardService rewardService =
                mock(SubscriptionVerificationRewardService.class);
        ObjectStorage objectStorage = mock(ObjectStorage.class);
        VisionAnalysisPort visionAnalysisPort = mock(VisionAnalysisPort.class);
        SubscriptionVerificationProcessingExecutorProperties properties =
                new SubscriptionVerificationProcessingExecutorProperties();
        Clock clock = Clock.fixed(Instant.parse("2026-09-29T00:00:00Z"), ZoneOffset.UTC);
        given(claimService.claim(123L, clock.instant(), properties.getProcessingLeaseDuration()))
                .willReturn(Optional.empty());
        SubscriptionVerificationProcessingWorker worker = new SubscriptionVerificationProcessingWorker(
                claimService,
                completionService,
                rewardService,
                objectStorage,
                visionAnalysisPort,
                properties,
                clock);

        worker.process(123L);

        then(objectStorage).should(never()).get(any());
        then(visionAnalysisPort).shouldHaveNoInteractions();
        then(completionService).shouldHaveNoInteractions();
        then(rewardService).shouldHaveNoInteractions();
    }

    @Test
    void Claim_성공_뒤_승인_판정을_저장한다() {
        TestFixture fixture = new TestFixture();
        SubscriptionVerificationProcessingClaim claim = claim();
        given(fixture.claimService.claim(123L, fixture.clock.instant(), fixture.properties.getProcessingLeaseDuration()))
                .willReturn(Optional.of(claim));
        given(fixture.objectStorage.get(claim.imageObjectKey())).willReturn(new byte[] {1, 2, 3});
        given(fixture.visionAnalysisPort.analyze(any())).willReturn(new VisionAnalysisResult(
                VisionPlatform.YOUTUBE,
                "채널",
                "@channel",
                VisionSubscriptionState.SUBSCRIBED,
                true,
                0.9));
        given(fixture.completionService.complete(
                eq(claim.verificationId()),
                eq(claim.processingToken()),
                eq(SubscriptionVerificationProcessingOutcome.APPROVED),
                eq(null),
                eq(fixture.clock.instant()))).willReturn(true);

        fixture.worker.process(123L);

        then(fixture.completionService).should().complete(
                eq(claim.verificationId()),
                eq(claim.processingToken()),
                eq(SubscriptionVerificationProcessingOutcome.APPROVED),
                eq(null),
                eq(fixture.clock.instant()));
        then(fixture.rewardService).should().reward(claim);
    }

    @Test
    void 재시도_가능한_Provider_실패는_완료하지_않고_Recovery에_맡긴다() {
        TestFixture fixture = new TestFixture();
        SubscriptionVerificationProcessingClaim claim = claim();
        given(fixture.claimService.claim(123L, fixture.clock.instant(), fixture.properties.getProcessingLeaseDuration()))
                .willReturn(Optional.of(claim));
        given(fixture.objectStorage.get(claim.imageObjectKey())).willReturn(new byte[] {1});
        willThrow(new VisionAnalysisException(
                VisionAnalysisFailureType.RETRYABLE, "provider unavailable"))
                .given(fixture.visionAnalysisPort).analyze(any());

        fixture.worker.process(123L);

        then(fixture.completionService).shouldHaveNoInteractions();
        then(fixture.rewardService).shouldHaveNoInteractions();
    }

    @Test
    void 재시도_불가능한_Provider_실패는_FAILED로_저장한다() {
        TestFixture fixture = new TestFixture();
        SubscriptionVerificationProcessingClaim claim = claim();
        given(fixture.claimService.claim(123L, fixture.clock.instant(), fixture.properties.getProcessingLeaseDuration()))
                .willReturn(Optional.of(claim));
        given(fixture.objectStorage.get(claim.imageObjectKey())).willReturn(new byte[] {1});
        willThrow(new VisionAnalysisException(
                VisionAnalysisFailureType.NON_RETRYABLE, "invalid provider request"))
                .given(fixture.visionAnalysisPort).analyze(any());

        fixture.worker.process(123L);

        then(fixture.completionService).should().complete(
                eq(claim.verificationId()),
                eq(claim.processingToken()),
                eq(SubscriptionVerificationProcessingOutcome.FAILED),
                eq("PROVIDER_NON_RETRYABLE"),
                eq(fixture.clock.instant()));
        then(fixture.rewardService).shouldHaveNoInteractions();
    }

    @Test
    void Object_처리_실패는_완료하지_않고_Recovery에_맡긴다() {
        TestFixture fixture = new TestFixture();
        SubscriptionVerificationProcessingClaim claim = claim();
        given(fixture.claimService.claim(123L, fixture.clock.instant(), fixture.properties.getProcessingLeaseDuration()))
                .willReturn(Optional.of(claim));
        willThrow(new IllegalStateException("object missing"))
                .given(fixture.objectStorage).get(claim.imageObjectKey());

        fixture.worker.process(123L);

        then(fixture.completionService).shouldHaveNoInteractions();
        then(fixture.rewardService).shouldHaveNoInteractions();
    }

    @Test
    void Provider_대기_중_인터럽트되면_완료_상태를_저장하지_않는다() {
        TestFixture fixture = new TestFixture();
        SubscriptionVerificationProcessingClaim claim = claim();
        given(fixture.claimService.claim(123L, fixture.clock.instant(), fixture.properties.getProcessingLeaseDuration()))
                .willReturn(Optional.of(claim));
        Thread.currentThread().interrupt();

        try {
            fixture.worker.process(123L);
        } finally {
            Thread.interrupted();
        }

        then(fixture.objectStorage).should().get(claim.imageObjectKey());
        then(fixture.visionAnalysisPort).shouldHaveNoInteractions();
        then(fixture.completionService).shouldHaveNoInteractions();
    }

    @Test
    void Provider_재시도_대기_인터럽트는_완료_상태를_저장하지_않는다() {
        TestFixture fixture = new TestFixture();
        SubscriptionVerificationProcessingClaim claim = claim();
        given(fixture.claimService.claim(123L, fixture.clock.instant(), fixture.properties.getProcessingLeaseDuration()))
                .willReturn(Optional.of(claim));
        given(fixture.objectStorage.get(claim.imageObjectKey())).willReturn(new byte[] {1});
        willThrow(new VisionAnalysisException(
                VisionAnalysisFailureType.RETRYABLE,
                "DeepSeek API 재시도가 중단되었습니다.",
                new InterruptedException()))
                .given(fixture.visionAnalysisPort).analyze(any());

        try {
            fixture.worker.process(123L);

            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }

        then(fixture.completionService).shouldHaveNoInteractions();
    }

    @Test
    void Object_처리_중_인터럽트된_일반_예외는_완료_상태를_저장하지_않는다() {
        TestFixture fixture = new TestFixture();
        SubscriptionVerificationProcessingClaim claim = claim();
        given(fixture.claimService.claim(123L, fixture.clock.instant(), fixture.properties.getProcessingLeaseDuration()))
                .willReturn(Optional.of(claim));
        willThrow(new IllegalStateException(new InterruptedException()))
                .given(fixture.objectStorage).get(claim.imageObjectKey());

        try {
            fixture.worker.process(123L);

            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }

        then(fixture.completionService).shouldHaveNoInteractions();
    }

    @Test
    void 실패_상태_저장도_실패하면_예외를_전파하지_않는다() {
        TestFixture fixture = new TestFixture();
        SubscriptionVerificationProcessingClaim claim = claim();
        given(fixture.claimService.claim(123L, fixture.clock.instant(), fixture.properties.getProcessingLeaseDuration()))
                .willReturn(Optional.of(claim));
        given(fixture.objectStorage.get(claim.imageObjectKey())).willReturn(new byte[] {1});
        willThrow(new VisionAnalysisException(
                VisionAnalysisFailureType.NON_RETRYABLE, "invalid provider request"))
                .given(fixture.visionAnalysisPort).analyze(any());
        willThrow(new IllegalStateException("database unavailable"))
                .given(fixture.completionService).complete(
                        eq(claim.verificationId()),
                        eq(claim.processingToken()),
                        eq(SubscriptionVerificationProcessingOutcome.FAILED),
                        eq("PROVIDER_NON_RETRYABLE"),
                        eq(fixture.clock.instant()));

        assertThatCode(() -> fixture.worker.process(123L)).doesNotThrowAnyException();

        then(fixture.completionService).should().complete(
                eq(claim.verificationId()),
                eq(claim.processingToken()),
                eq(SubscriptionVerificationProcessingOutcome.FAILED),
                eq("PROVIDER_NON_RETRYABLE"),
                eq(fixture.clock.instant()));
    }

    @Test
    void 판정_결과_저장_실패는_실패_상태_저장을_재시도하지_않는다() {
        TestFixture fixture = new TestFixture();
        SubscriptionVerificationProcessingClaim claim = claim();
        given(fixture.claimService.claim(123L, fixture.clock.instant(), fixture.properties.getProcessingLeaseDuration()))
                .willReturn(Optional.of(claim));
        given(fixture.objectStorage.get(claim.imageObjectKey())).willReturn(new byte[] {1});
        given(fixture.visionAnalysisPort.analyze(any())).willReturn(new VisionAnalysisResult(
                VisionPlatform.YOUTUBE,
                "채널",
                "@channel",
                VisionSubscriptionState.SUBSCRIBED,
                true,
                0.9));
        willThrow(new IllegalStateException("database unavailable"))
                .given(fixture.completionService).complete(
                        eq(claim.verificationId()),
                        eq(claim.processingToken()),
                        eq(SubscriptionVerificationProcessingOutcome.APPROVED),
                        eq(null),
                        eq(fixture.clock.instant()));

        fixture.worker.process(123L);

        then(fixture.completionService).should().complete(
                eq(claim.verificationId()),
                eq(claim.processingToken()),
                eq(SubscriptionVerificationProcessingOutcome.APPROVED),
                eq(null),
                eq(fixture.clock.instant()));
        then(fixture.completionService).shouldHaveNoMoreInteractions();
        then(fixture.rewardService).shouldHaveNoInteractions();
    }

    @Test
    void 승인_판정_저장_소유권을_잃으면_보상을_호출하지_않는다() {
        TestFixture fixture = new TestFixture();
        SubscriptionVerificationProcessingClaim claim = claim();
        given(fixture.claimService.claim(123L, fixture.clock.instant(), fixture.properties.getProcessingLeaseDuration()))
                .willReturn(Optional.of(claim));
        given(fixture.objectStorage.get(claim.imageObjectKey())).willReturn(new byte[] {1});
        given(fixture.visionAnalysisPort.analyze(any())).willReturn(new VisionAnalysisResult(
                VisionPlatform.YOUTUBE,
                "채널",
                "@channel",
                VisionSubscriptionState.SUBSCRIBED,
                true,
                0.9));
        given(fixture.completionService.complete(any(), any(), any(), any(), any())).willReturn(false);

        fixture.worker.process(123L);

        then(fixture.rewardService).shouldHaveNoInteractions();
    }

    @Test
    void 거절_판정은_저장하지만_보상을_호출하지_않는다() {
        TestFixture fixture = new TestFixture();
        SubscriptionVerificationProcessingClaim claim = claim();
        given(fixture.claimService.claim(123L, fixture.clock.instant(), fixture.properties.getProcessingLeaseDuration()))
                .willReturn(Optional.of(claim));
        given(fixture.objectStorage.get(claim.imageObjectKey())).willReturn(new byte[] {1});
        given(fixture.visionAnalysisPort.analyze(any())).willReturn(new VisionAnalysisResult(
                VisionPlatform.OTHER,
                "다른 채널",
                "@other",
                VisionSubscriptionState.NOT_SUBSCRIBED,
                true,
                0.9));

        fixture.worker.process(123L);

        then(fixture.completionService).should().complete(
                claim.verificationId(),
                claim.processingToken(),
                SubscriptionVerificationProcessingOutcome.REJECTED,
                "PLATFORM_MISMATCH",
                fixture.clock.instant());
        then(fixture.rewardService).shouldHaveNoInteractions();
    }

    @Test
    void 증거_부족_판정은_재제출_필요로_저장하고_보상을_호출하지_않는다() {
        TestFixture fixture = new TestFixture();
        SubscriptionVerificationProcessingClaim claim = claim();
        given(fixture.claimService.claim(123L, fixture.clock.instant(), fixture.properties.getProcessingLeaseDuration()))
                .willReturn(Optional.of(claim));
        given(fixture.objectStorage.get(claim.imageObjectKey())).willReturn(new byte[] {1});
        given(fixture.visionAnalysisPort.analyze(any())).willReturn(new VisionAnalysisResult(
                VisionPlatform.YOUTUBE,
                "채널",
                "@channel",
                VisionSubscriptionState.SUBSCRIBED,
                false,
                0.9));

        fixture.worker.process(123L);

        then(fixture.completionService).should().complete(
                claim.verificationId(),
                claim.processingToken(),
                SubscriptionVerificationProcessingOutcome.RETRY_REQUIRED,
                "INSUFFICIENT_EVIDENCE",
                fixture.clock.instant());
        then(fixture.rewardService).shouldHaveNoInteractions();
    }

    private static SubscriptionVerificationProcessingClaim claim() {
        return new SubscriptionVerificationProcessingClaim(
                123L,
                UUID.randomUUID().toString(),
                7L,
                42L,
                103L,
                "subscription-verifications/123.jpg",
                "채널",
                "@channel",
                "20000000-0000-4000-8000-000000000001",
                "2026-09-29",
                1,
                Instant.parse("2026-09-29T00:01:30Z"));
    }

    private static final class TestFixture {

        private final SubscriptionVerificationProcessingClaimService claimService =
                mock(SubscriptionVerificationProcessingClaimService.class);
        private final SubscriptionVerificationProcessingCompletionService completionService =
                mock(SubscriptionVerificationProcessingCompletionService.class);
        private final SubscriptionVerificationRewardService rewardService =
                mock(SubscriptionVerificationRewardService.class);
        private final ObjectStorage objectStorage = mock(ObjectStorage.class);
        private final VisionAnalysisPort visionAnalysisPort = mock(VisionAnalysisPort.class);
        private final SubscriptionVerificationProcessingExecutorProperties properties =
                new SubscriptionVerificationProcessingExecutorProperties();
        private final Clock clock = Clock.fixed(Instant.parse("2026-09-29T00:00:00Z"), ZoneOffset.UTC);
        private final SubscriptionVerificationProcessingWorker worker = new SubscriptionVerificationProcessingWorker(
                claimService,
                completionService,
                rewardService,
                objectStorage,
                visionAnalysisPort,
                properties,
                clock);
    }
}
