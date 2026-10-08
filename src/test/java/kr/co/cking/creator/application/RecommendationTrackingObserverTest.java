package kr.co.cking.creator.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import kr.co.cking.creator.application.dto.PersonalizedCreatorRecommendationView;
import kr.co.cking.follow.application.CreatorFollowCreated;
import org.junit.jupiter.api.Test;

class RecommendationTrackingObserverTest {
    private final RecommendationTrackingService service = mock(RecommendationTrackingService.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final RecommendationTrackingObserver observer = new RecommendationTrackingObserver(service, meters, Runnable::run);
    private final CreatorFollowCreated follow = new CreatorFollowCreated(7L, 20L, Instant.parse("2026-10-08T00:00:00Z"));

    @Test
    void 스냅샷_실패는_null과_실패_지표로_분리한다() {
        when(service.recordSnapshot(eq(7L), any())).thenThrow(new IllegalStateException());
        assertThat(observer.snapshot(7L, new PersonalizedCreatorRecommendationView("POPULAR_FALLBACK_V1", List.of()))).isNull();
        assertThat(meters.get("cking.recommendation.tracking").tags("phase", "snapshot", "outcome", "failure").counter().count()).isEqualTo(1);
    }

    @Test
    void 팔로우_저장_실패는_호출자에게_전파하지_않는다() {
        doThrow(new IllegalStateException()).when(service).recordFollow(7L, 20L, follow.followedAt());
        assertThatCode(() -> observer.onFollowCreated(follow)).doesNotThrowAnyException();
        assertThat(meters.get("cking.recommendation.tracking").tags("phase", "follow", "outcome", "failure").counter().count()).isEqualTo(1);
    }

    @Test
    void 큐_포화도_팔로우_성공에_영향을_주지_않고_실패_지표를_남긴다() {
        var rejected = new RecommendationTrackingObserver(service, meters, task -> { throw new RejectedExecutionException(); });
        assertThatCode(() -> rejected.onFollowCreated(follow)).doesNotThrowAnyException();
        verifyNoInteractions(service);
        assertThat(meters.get("cking.recommendation.tracking").tags("phase", "follow_dispatch", "outcome", "failure").counter().count()).isEqualTo(1);
    }

    @Test
    void 스냅샷_성공_ID와_커밋된_팔로우_정보를_전달한다() {
        UUID id = UUID.randomUUID();
        var view = new PersonalizedCreatorRecommendationView("POPULAR_FALLBACK_V1", List.of());
        when(service.recordSnapshot(7L, view)).thenReturn(id);
        assertThat(observer.snapshot(7L, view)).isEqualTo(id);
        observer.onFollowCreated(follow);
        verify(service).recordFollow(7L, 20L, follow.followedAt());
    }
}
