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

    @Scheduled(fixedDelayString = "${cking.recommendation.tracking.cleanup-interval-ms:60000}",
            initialDelayString = "${cking.recommendation.tracking.cleanup-interval-ms:60000}")
    public void cleanUp() {
        try {
            // 각 100건은 별도 트랜잭션이다. 뒤 배치의 timeout이 앞 배치까지 되돌리지 않는다.
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(30);
            for (int batch = 0; batch < 100 && System.nanoTime() < deadline; batch++) {
                int requests = service.cleanUp();
                int events = service.cleanUpFollowEvents();
                if (requests < 100 && events < 100) break;
            }
            observer.result("cleanup", "success");
        } catch (RuntimeException failure) {
            observer.failed("cleanup", failure);
        }
    }
}
