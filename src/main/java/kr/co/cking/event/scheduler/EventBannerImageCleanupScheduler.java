package kr.co.cking.event.scheduler;

import java.time.Clock;
import java.time.Duration;
import kr.co.cking.common.storage.ObjectStorage;
import kr.co.cking.event.repository.EventBannerImageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 24시간 안에 연결되지 않은 업로드와 교체·제거된 Event 배너를 재시도 가능하게 정리한다(이슈 #748).
 * 삭제 대기는 10분 뒤에 지워, 직전까지 응답·캐시에 남아 있던 주소가 바로 깨지지 않게 한다.
 */
@Component @RequiredArgsConstructor @Slf4j
public class EventBannerImageCleanupScheduler {
    private final EventBannerImageRepository repository;
    private final ObjectStorage objectStorage;
    private final Clock clock;

    @Scheduled(fixedDelayString = "${cking.event.banner-image.cleanup-interval-ms:600000}")
    public void cleanUp() {
        var now = clock.instant();
        repository.claimStaleUnlinked(now.minus(Duration.ofHours(24)), now);
        repository.findDeletePending(now.minus(Duration.ofMinutes(10)), PageRequest.of(0, 100)).forEach(image -> {
            try { objectStorage.delete(image.getObjectKey()); repository.deleteDeletePending(image.getObjectKey()); }
            catch (RuntimeException exception) { repository.postponeDeletePending(image.getObjectKey(), now); log.warn("Event 배너 이미지 정리 실패. objectKey={}", image.getObjectKey()); }
        });
    }
}
