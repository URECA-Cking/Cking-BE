package kr.co.cking.creator.scheduler;

import kr.co.cking.creator.application.RecommendationTrackingObserver;
import kr.co.cking.creator.application.RecommendationTrackingService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "cking.recommendation.tracking.recovery-enabled", havingValue = "true", matchIfMissing = true)
public class RecommendationTrackingRecovery {
    private final RecommendationTrackingService service;
    private final RecommendationTrackingObserver observer;

    @Scheduled(fixedDelayString = "${cking.recommendation.tracking.recovery-interval-ms:60000}",
            initialDelayString = "${cking.recommendation.tracking.recovery-interval-ms:60000}")
    public void recover() {
        try {
            for (var id : service.pendingFollowEvents()) {
                try {
                    // 건별 REQUIRES_NEW: 한 건의 실패가 나머지 복구를 롤백하지 않는다.
                    service.recoverFollowEvent(id);
                    observer.result("follow_recovery", "success");
                } catch (RuntimeException failure) {
                    observer.failed("follow_recovery", failure);
                    service.deferFollowEvent(id);
                }
            }
        } catch (RuntimeException failure) {
            observer.failed("follow_recovery", failure);
        }
    }
}
