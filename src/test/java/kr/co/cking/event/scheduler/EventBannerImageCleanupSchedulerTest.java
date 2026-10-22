package kr.co.cking.event.scheduler;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import kr.co.cking.common.storage.ObjectStorage;
import kr.co.cking.event.domain.EventBannerImage;
import kr.co.cking.event.repository.EventBannerImageRepository;
import org.junit.jupiter.api.Test;

class EventBannerImageCleanupSchedulerTest {

    private final Instant now = Instant.parse("2026-10-22T00:00:00Z");
    private final EventBannerImageRepository images = org.mockito.Mockito.mock(EventBannerImageRepository.class);
    private final ObjectStorage storage = org.mockito.Mockito.mock(ObjectStorage.class);
    private final EventBannerImageCleanupScheduler scheduler =
            new EventBannerImageCleanupScheduler(images, storage, Clock.fixed(now, ZoneOffset.UTC));

    @Test
    void 하루_지난_미연결_업로드를_선점하고_10분_지난_삭제_대기는_객체와_기록을_지운다() {
        given(images.findDeletePending(any(), any())).willReturn(List.of(image("a.jpg")));

        scheduler.cleanUp();

        then(images).should().claimStaleUnlinked(now.minus(Duration.ofHours(24)), now);
        then(images).should().findDeletePending(org.mockito.ArgumentMatchers.eq(now.minus(Duration.ofMinutes(10))), any());
        then(storage).should().delete("a.jpg");
        then(images).should().deleteDeletePending("a.jpg");
    }

    @Test
    void 객체_삭제에_실패하면_기록을_남기고_다음_실행으로_미룬다() {
        given(images.findDeletePending(any(), any())).willReturn(List.of(image("a.jpg"), image("b.jpg")));
        willThrow(new RuntimeException("S3")).given(storage).delete("a.jpg");

        scheduler.cleanUp();

        then(images).should().postponeDeletePending("a.jpg", now);
        then(images).should(never()).deleteDeletePending("a.jpg");
        then(images).should().deleteDeletePending("b.jpg");
    }

    private EventBannerImage image(String key) {
        return EventBannerImage.uploading(key, 42L, now);
    }
}
