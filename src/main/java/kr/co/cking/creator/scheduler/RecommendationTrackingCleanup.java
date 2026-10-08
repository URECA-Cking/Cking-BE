package kr.co.cking.creator.scheduler;

import kr.co.cking.creator.application.RecommendationTrackingObserver;
import kr.co.cking.creator.application.RecommendationTrackingService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "cking.recommendation.tracking.cleanup-enabled", havingValue = "true", matchIfMissing = true)
public class RecommendationTrackingCleanup {
    private final RecommendationTrackingService service;
    private final RecommendationTrackingObserver observer;

    @Scheduled(fixedDelayString = "${cking.recommendation.tracking.cleanup-interval-ms:3600000}",
            initialDelayString = "${cking.recommendation.tracking.cleanup-interval-ms:3600000}")
    public void cleanUp() {
        try {
            service.cleanUp();
            observer.result("cleanup", "success");
        } catch (RuntimeException failure) {
            observer.failed("cleanup", failure);
        }
    }
}
