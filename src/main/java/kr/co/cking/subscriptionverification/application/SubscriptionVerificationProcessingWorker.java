package kr.co.cking.subscriptionverification.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.Semaphore;
import kr.co.cking.common.storage.ObjectStorage;
import kr.co.cking.subscriptionverification.application.vision.SubscriptionVerificationDecision;
import kr.co.cking.subscriptionverification.application.vision.SubscriptionVerificationDecisionPolicy;
import kr.co.cking.subscriptionverification.application.vision.SubscriptionVerificationDecisionStatus;
import kr.co.cking.subscriptionverification.application.vision.VisionAnalysisException;
import kr.co.cking.subscriptionverification.application.vision.VisionAnalysisPort;
import kr.co.cking.subscriptionverification.application.vision.VisionAnalysisRequest;
import kr.co.cking.subscriptionverification.infrastructure.async.SubscriptionVerificationProcessingExecutorProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/** Claim을 얻은 Executor thread에서만 Object와 VLM을 처리하는 구독 인증 Worker다. */
@Service
@Slf4j
public class SubscriptionVerificationProcessingWorker {

    private static final double CONFIDENCE_THRESHOLD = 0.8;

    private final SubscriptionVerificationProcessingClaimService claimService;
    private final SubscriptionVerificationProcessingCompletionService completionService;
    private final ObjectStorage objectStorage;
    private final VisionAnalysisPort visionAnalysisPort;
    private final SubscriptionVerificationDecisionPolicy decisionPolicy;
    private final SubscriptionVerificationProcessingExecutorProperties properties;
    private final Clock clock;
    private final Semaphore providerCalls;

    /** Claim·완료 Service와 Provider 호출 제한기를 조립한다. */
    public SubscriptionVerificationProcessingWorker(
            SubscriptionVerificationProcessingClaimService claimService,
            SubscriptionVerificationProcessingCompletionService completionService,
            ObjectStorage objectStorage,
            VisionAnalysisPort visionAnalysisPort,
            SubscriptionVerificationProcessingExecutorProperties properties,
            Clock clock) {
        this.claimService = claimService;
        this.completionService = completionService;
        this.objectStorage = objectStorage;
        this.visionAnalysisPort = visionAnalysisPort;
        this.decisionPolicy = new SubscriptionVerificationDecisionPolicy(CONFIDENCE_THRESHOLD);
        this.properties = properties;
        this.clock = clock;
        this.providerCalls = new Semaphore(properties.getProviderMaxConcurrentCalls(), true);
    }

    /** 전용 Executor에서 Claim을 먼저 얻고, 소유한 작업만 VLM 판정으로 진행한다. */
    @Async("subscriptionVerificationExecutor")
    public void process(Long verificationId) {
        Optional<SubscriptionVerificationProcessingClaim> claimed = claimService.claim(
                verificationId, clock.instant(), properties.getProcessingLeaseDuration());
        claimed.ifPresent(this::processClaimedVerification);
    }

    /** 선점한 Verification의 Object를 읽고 Provider 관측값을 최종 상태로 저장한다. */
    private void processClaimedVerification(SubscriptionVerificationProcessingClaim claim) {
        try {
            byte[] image = objectStorage.get(claim.imageObjectKey());
            SubscriptionVerificationDecision decision = analyzeWithProviderLimit(claim, image);
            completionService.complete(
                    claim.verificationId(),
                    claim.processingToken(),
                    outcomeOf(decision),
                    decision.reasonCode(),
                    clock.instant());
        } catch (VisionAnalysisException exception) {
            completeFailed(claim, "PROVIDER_FAILURE", exception);
        } catch (RuntimeException exception) {
            completeFailed(claim, "PROCESSING_FAILURE", exception);
        }
    }

    /** Provider 호출 수를 설정 한도 이내로 유지하면서 VLM 관측값을 판정한다. */
    private SubscriptionVerificationDecision analyzeWithProviderLimit(
            SubscriptionVerificationProcessingClaim claim,
            byte[] image) {
        boolean acquired = false;
        try {
            providerCalls.acquire();
            acquired = true;
            return decisionPolicy.decide(
                    claim.targetChannelHandle(),
                    visionAnalysisPort.analyze(new VisionAnalysisRequest(
                            image, claim.targetChannelName(), claim.targetChannelHandle())));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new VisionAnalysisException(
                    kr.co.cking.subscriptionverification.application.vision.VisionAnalysisFailureType.RETRYABLE,
                    "Provider 호출 대기 중 작업이 중단되었습니다.",
                    exception);
        } finally {
            if (acquired) {
                providerCalls.release();
            }
        }
    }

    /** 서버 판정 결과를 Claim 완료 Service가 이해하는 종료 상태로 변환한다. */
    private SubscriptionVerificationProcessingOutcome outcomeOf(
            SubscriptionVerificationDecision decision) {
        return switch (decision.status()) {
            case APPROVED -> SubscriptionVerificationProcessingOutcome.APPROVED;
            case REJECTED -> SubscriptionVerificationProcessingOutcome.REJECTED;
            case RETRY_REQUIRED -> SubscriptionVerificationProcessingOutcome.RETRY_REQUIRED;
        };
    }

    /** VLM·Object 처리 실패를 현재 fencing token의 FAILED 상태로 기록한다. */
    private void completeFailed(
            SubscriptionVerificationProcessingClaim claim,
            String reasonCode,
            RuntimeException exception) {
        log.warn("구독 인증 비동기 처리에 실패했습니다. verificationId={}, reasonCode={}",
                claim.verificationId(), reasonCode, exception);
        completionService.complete(
                claim.verificationId(),
                claim.processingToken(),
                SubscriptionVerificationProcessingOutcome.FAILED,
                reasonCode,
                Instant.now(clock));
    }
}
