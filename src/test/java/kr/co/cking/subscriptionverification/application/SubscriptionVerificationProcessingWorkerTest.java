package kr.co.cking.subscriptionverification.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import kr.co.cking.common.storage.ObjectStorage;
import kr.co.cking.subscriptionverification.application.vision.VisionAnalysisPort;
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
                objectStorage,
                visionAnalysisPort,
                properties,
                clock);

        worker.process(123L);

        then(objectStorage).should(never()).get(any());
        then(visionAnalysisPort).shouldHaveNoInteractions();
        then(completionService).shouldHaveNoInteractions();
    }
}
