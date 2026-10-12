package kr.co.cking.member.scheduler;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import kr.co.cking.member.application.MemberProfileImageStorageCleaner;
import kr.co.cking.member.domain.MemberProfileImage;
import kr.co.cking.member.repository.MemberProfileImageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 24시간 동안 프로필에 연결되지 않은 업로드와 실패한 삭제를 정리한다. */
@Component
@RequiredArgsConstructor
@Slf4j
public class MemberProfileImageCleanupScheduler {

    static final Duration UNLINKED_RETENTION = Duration.ofHours(24);
    static final Duration DELETE_PENDING_GRACE = Duration.ofMinutes(10);
    static final int BATCH_SIZE = 100;

    private final MemberProfileImageRepository profileImageRepository;
    private final MemberProfileImageStorageCleaner storageCleaner;
    private final Clock clock;

    @Scheduled(
            fixedDelayString = "${cking.member.profile-image.cleanup-interval-ms:600000}",
            initialDelayString = "${cking.member.profile-image.cleanup-interval-ms:600000}"
    )
    public void cleanUp() {
        Instant now = clock.instant();
        profileImageRepository.claimStaleUnlinked(now.minus(UNLINKED_RETENTION), now);
        List<MemberProfileImage> pending = profileImageRepository.findDeletePending(
                now.minus(DELETE_PENDING_GRACE), PageRequest.of(0, BATCH_SIZE));
        for (MemberProfileImage image : pending) {
            try {
                storageCleaner.delete(image.getObjectKey());
            } catch (RuntimeException exception) {
                log.warn("프로필 이미지 정리에 실패했습니다. objectKey={}", image.getObjectKey(), exception);
                profileImageRepository.postponeDeletePending(image.getObjectKey(), now);
            }
        }
    }
}
