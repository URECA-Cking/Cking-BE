package kr.co.cking.creator.scheduler;

import java.time.Clock;
import java.time.Duration;
import kr.co.cking.common.storage.ObjectStorage;
import kr.co.cking.creator.domain.CreatorSpaceTemplateImageStatus;
import kr.co.cking.creator.repository.CreatorSpaceTemplateImageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 템플릿에 쓰이지 않는 관리자 템플릿 이미지를 정리한다(이슈 #598). 올린 지 24시간이 지났고 어떤 템플릿·Space의 URL에도
 * 들어 있지 않은 업로드만 삭제 대기로 선점한다. 템플릿 값은 Creator 승인 시점에 Space로 복사되어 기본 이미지·기본 배너로
 * 쓰이므로 "현재 템플릿에 없다"만으로는 지우지 않는다.
 *
 * <p>저장소 객체 삭제는 DB 트랜잭션에 묶을 수 없다. 그래서 삭제 직전에 참조를 다시 확인하는 것만으로는 부족하고(확인과 삭제
 * 사이에 관리자가 같은 URL을 템플릿에 저장하면 삭제된 이미지를 가리키게 된다), 삭제를 {@code DELETE_PENDING → DELETING}
 * 조건부 UPDATE로 먼저 확정한 뒤에만 지운다. 템플릿 저장은 같은 행을 {@code CreatorSpaceTemplateImagePinner}로 고정하므로
 * 둘 중 하나만 성공한다: 고정이 먼저면 확정이 실패해 삭제하지 않고, 확정이 먼저면 템플릿 저장이 거절된다.
 *
 * <p>삭제를 확정한 행은 지운 뒤에도 {@code DELETED}로 남긴다. 삭제 실패는 재시도 시각을 미뤄 다음 주기에 다시 시도하며, 이미
 * 삭제를 확정한 행은 참조 확인 없이 저장소 삭제만 재시도한다. 업로드 기록이 없는 이미지(#598 이전에 올린 것)는 대상이 아니다.
 */
@Component @RequiredArgsConstructor @Slf4j
public class CreatorSpaceTemplateImageCleanupScheduler {
    private static final Duration UNREFERENCED_GRACE = Duration.ofHours(24);
    private static final Duration DELETE_GRACE = Duration.ofMinutes(10);
    private static final int BATCH_SIZE = 100;

    private final CreatorSpaceTemplateImageRepository repository;
    private final ObjectStorage objectStorage;
    private final Clock clock;

    @Scheduled(fixedDelayString = "${cking.creator.space-template-image.cleanup-interval-ms:600000}")
    public void cleanUp() {
        var now = clock.instant();
        repository.claimStaleUnreferenced(now.minus(UNREFERENCED_GRACE), now);
        repository.findPendingDeletion(now.minus(DELETE_GRACE), PageRequest.of(0, BATCH_SIZE)).forEach(image -> {
            String key = image.getObjectKey();
            try {
                if (image.getStatus() == CreatorSpaceTemplateImageStatus.DELETE_PENDING) {
                    // 선점한 뒤 쓰이게 됐으면 되돌리고, 삭제 확정에 실패하면 그 사이 템플릿 저장이 이미지를 고정한 것이다.
                    if (repository.restoreIfReferenced(key, now) == 1 || repository.markDeleting(key, now) == 0) {
                        return;
                    }
                }
                objectStorage.delete(key);
                repository.markDeleted(key, now);
            } catch (RuntimeException exception) {
                repository.postponeDeletion(key, now);
                log.warn("Creator Space 템플릿 이미지 정리 실패. objectKey={}", key, exception);
            }
        });
    }
}
