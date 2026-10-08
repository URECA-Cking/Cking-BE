package kr.co.cking.creator.application;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.UUID;
import java.util.concurrent.Executor;
import kr.co.cking.creator.application.dto.PersonalizedCreatorRecommendationView;
import kr.co.cking.common.event.CreatorFollowCreated;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** 분석 장애를 주요 조회/팔로우와 격리한다. 수집 API는 실패를 숨기지 않는다. */
@Component
@Slf4j
public class RecommendationTrackingObserver {
    private final RecommendationTrackingService service;
    private final MeterRegistry meters;
    private final Executor executor;

    public RecommendationTrackingObserver(RecommendationTrackingService service, MeterRegistry meters,
                                         @Qualifier("recommendationTrackingExecutor") Executor executor) {
        this.service = service;
        this.meters = meters;
        this.executor = executor;
    }

    public UUID snapshot(Long memberId, PersonalizedCreatorRecommendationView view) {
        try {
            UUID id = service.recordSnapshot(memberId, view);
            result("snapshot", "success");
            return id;
        } catch (RuntimeException failure) {
            failed("snapshot", failure);
            return null;
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onFollowCreated(CreatorFollowCreated event) {
        try {
            // 커밋 콜백이 원래 연결을 반환하도록 먼저 큐에 넣어 연결 풀 고갈을 피한다.
            executor.execute(() -> {
                try {
                    service.processFollowEvent(event.eventId());
                    result("follow", "success");
                } catch (RuntimeException failure) {
                    failed("follow", failure);
                }
            });
        } catch (RuntimeException rejected) {
            failed("follow_dispatch", rejected);
        }
    }

    public void failed(String phase, RuntimeException failure) {
        result(phase, "failure");
        // 예외 메시지는 SQL/사용자 데이터가 섞일 수 있어 클래스만 기록한다.
        log.warn("Recommendation tracking failed: phase={}, error={}", phase, failure.getClass().getSimpleName());
    }

    public void result(String phase, String outcome) {
        meters.counter("cking.recommendation.tracking", "phase", phase, "outcome", outcome).increment();
    }
}
