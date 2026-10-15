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
 * 사이에 같은 URL이 템플릿이나 Space에 기록되면 삭제된 이미지를 가리키게 된다), 삭제를 {@code DELETE_PENDING → DELETING}
 * 조건부 UPDATE로 먼저 확정한 뒤에만 지운다. URL을 기록하는 경로(템플릿 생성·수정, Creator 승인 때의 Space 생성, Creator의
 * 프로필 URL 수정)는 같은 행을 {@code CreatorSpaceTemplateImagePinner}로 고정하므로 둘 중 하나만 성공한다: 고정이 먼저면
 * 확정이 실패해 삭제하지 않고, 확정이 먼저면 그 경로의 저장이 거절된다.
 *
 * <p>삭제를 확정한 행은 지운 뒤에도 {@code DELETED}로 남긴다. 삭제 실패는 재시도 시각을 미뤄 다음 주기에 다시 시도하며, 이미
 * 삭제를 확정한 행은 참조 확인 없이 저장소 삭제만 재시도한다. 업로드 기록이 없는 이미지(#598 이전에 올린 것)는 대상이 아니다.
 * 한 번 실행에서 처리한 개수는 하나라도 있으면 INFO 로그로 남긴다.
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
        int claimed = repository.claimStaleUnreferenced(now.minus(UNREFERENCED_GRACE), now);
        int deleted = 0;
        int restored = 0;
        int lostToPin = 0;
        int failed = 0;
        for (var image : repository.findPendingDeletion(now.minus(DELETE_GRACE), PageRequest.of(0, BATCH_SIZE))) {
            String key = image.getObjectKey();
            try {
                if (image.getStatus() == CreatorSpaceTemplateImageStatus.DELETE_PENDING) {
                    // 선점한 뒤 쓰이게 됐으면 되돌린다.
                    if (repository.restoreIfReferenced(key, now) == 1) {
                        restored++;
                        continue;
                    }
                    // 삭제 확정에 실패하면 그 사이 URL을 기록하는 경로가 이미지를 고정한 것이다.
                    if (repository.markDeleting(key, now) == 0) {
                        lostToPin++;
                        continue;
                    }
                }
                objectStorage.delete(key);
                repository.markDeleted(key, now);
                deleted++;
            } catch (RuntimeException exception) {
                failed++;
                repository.postponeDeletion(key, now);
                log.warn("Creator Space 템플릿 이미지 정리 실패. objectKey={}", key, exception);
            }
        }
        if (claimed + deleted + restored + lostToPin + failed > 0) {
            log.info("Creator Space 템플릿 이미지 정리: 삭제 대기로 선점 {}건, 저장소 삭제 {}건, 참조 확인으로 정리 취소 {}건, "
                    + "삭제 확정 직전 사용 시작으로 취소 {}건, 삭제 실패 {}건", claimed, deleted, restored, lostToPin, failed);
        }
    }
}
