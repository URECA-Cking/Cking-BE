package kr.co.cking.post.application;

import kr.co.cking.post.domain.CreatorPostImage;
import kr.co.cking.post.repository.CreatorPostImageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * 게시글에 연결되지 않은 오래된 업로드와 저장소 삭제가 끝나지 않은 이미지를 정리한다(이슈 #318).
 *
 * <ol>
 *   <li>{@link #UNLINKED_RETENTION}이 지나도록 연결되지 않은 UPLOADING·UPLOADED 기록을 조건부 UPDATE로
 *       DELETE_PENDING으로 선점한다. 같은 이미지를 게시글에 연결하는 요청과 경합해도 한쪽만 성공한다.</li>
 *   <li>DELETE_PENDING이 된 지 {@link #DELETE_PENDING_GRACE}가 지난 기록을 저장소에서 지우고 기록을 삭제한다.
 *       유예 시간은 방금 Commit된 게시글 수정·삭제의 AFTER_COMMIT 삭제와 겹치지 않게 하기 위함이다.</li>
 * </ol>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PostImageCleanupScheduler {

    static final Duration UNLINKED_RETENTION = Duration.ofHours(24);
    static final Duration DELETE_PENDING_GRACE = Duration.ofMinutes(10);
    static final int BATCH_SIZE = 100;

    private final CreatorPostImageRepository imageRepository;
    private final PostImageStorageCleaner storageCleaner;
    private final Clock clock;

    @Scheduled(
            fixedDelayString = "${cking.post.image-cleanup-interval-ms:600000}",
            initialDelayString = "${cking.post.image-cleanup-interval-ms:600000}"
    )
    public void cleanUp() {
        Instant now = clock.instant();
        int claimed = imageRepository.claimStaleUnlinked(now.minus(UNLINKED_RETENTION), now);
        if (claimed > 0) {
            log.info("연결되지 않은 게시글 이미지를 삭제 대기로 바꿨습니다. count={}", claimed);
        }

        List<CreatorPostImage> pending = imageRepository.findDeletePending(
                now.minus(DELETE_PENDING_GRACE), PageRequest.of(0, BATCH_SIZE));
        for (CreatorPostImage image : pending) {
            try {
                storageCleaner.delete(image.getObjectKey());
            } catch (RuntimeException exception) {
                log.warn("게시글 이미지 정리에 실패했습니다. 다음 실행에서 다시 시도합니다. objectKey={}",
                        image.getObjectKey(), exception);
            }
        }
    }
}
