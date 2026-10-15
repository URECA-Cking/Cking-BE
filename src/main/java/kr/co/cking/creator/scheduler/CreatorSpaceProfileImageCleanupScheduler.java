package kr.co.cking.creator.scheduler;

import java.time.Clock;
import java.time.Duration;
import kr.co.cking.common.storage.ObjectStorage;
import kr.co.cking.creator.repository.CreatorSpaceProfileImageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 연결되지 않은 업로드와 교체된 Creator Space 이미지를 재시도 가능하게 정리한다. */
@Component @RequiredArgsConstructor @Slf4j
public class CreatorSpaceProfileImageCleanupScheduler {
    private final CreatorSpaceProfileImageRepository repository;
    private final ObjectStorage objectStorage;
    private final Clock clock;
    @Scheduled(fixedDelayString = "${cking.creator.space-profile-image.cleanup-interval-ms:600000}")
    public void cleanUp() {
        var now = clock.instant();
        repository.claimStaleUnlinked(now.minus(Duration.ofHours(24)), now);
        repository.findDeletePending(now.minus(Duration.ofMinutes(10)), PageRequest.of(0, 100)).forEach(image -> {
            try { objectStorage.delete(image.getObjectKey()); repository.deleteDeletePending(image.getObjectKey()); }
            catch (RuntimeException exception) { repository.postponeDeletePending(image.getObjectKey(), now); log.warn("Creator Space 프로필 이미지 정리 실패. objectKey={}", image.getObjectKey()); }
        });
    }
}
