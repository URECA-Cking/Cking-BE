package kr.co.cking.upload.scheduler;

import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.times;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import kr.co.cking.upload.repository.ImageUploadRepository;
import org.junit.jupiter.api.Test;

class ImageUploadCleanupSchedulerTest {

    private final Instant now = Instant.parse("2026-10-29T00:00:00Z");
    private final ImageUploadRepository repository = org.mockito.Mockito.mock(ImageUploadRepository.class);
    private final ImageUploadCleanupScheduler scheduler =
            new ImageUploadCleanupScheduler(repository, Clock.fixed(now, ZoneOffset.UTC));

    @Test
    void 한_시간_지난_PENDING을_끝내고_하루_지난_기록을_지운다() {
        given(repository.deleteCreatedBefore(now.minus(Duration.ofDays(1)), 1000)).willReturn(3);

        scheduler.cleanUp();

        then(repository).should().expirePending(now.minus(Duration.ofHours(1)), now);
        then(repository).should(times(1)).deleteCreatedBefore(now.minus(Duration.ofDays(1)), 1000);
    }

    @Test
    void 지울_기록이_한_번에_다_안_지워지면_이어서_지운다() {
        given(repository.deleteCreatedBefore(now.minus(Duration.ofDays(1)), 1000)).willReturn(1000, 1000, 10);

        scheduler.cleanUp();

        then(repository).should(times(3)).deleteCreatedBefore(now.minus(Duration.ofDays(1)), 1000);
    }
}
