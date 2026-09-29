package kr.co.cking.post.application;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 게시글 수정·삭제가 Commit된 뒤에만 빠진 이미지를 저장소에서 지운다.
 *
 * <p>Transaction 안에서 먼저 지우면 이후 Rollback 때 게시글은 남고 이미지만 사라질 수 있다. 여기서 실패한 이미지는
 * DELETE_PENDING 기록이 남아 정리 스케줄러가 다시 처리하므로 예외를 호출자에게 전파하지 않는다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
class PostImageDeletionListener {

    private final PostImageStorageCleaner storageCleaner;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onReleased(PostImagesReleasedEvent event) {
        for (String objectKey : event.objectKeys()) {
            try {
                storageCleaner.delete(objectKey);
            } catch (RuntimeException exception) {
                log.warn("게시글 이미지 삭제에 실패했습니다. 정리 스케줄러가 다시 시도합니다. objectKey={}",
                        objectKey, exception);
            }
        }
    }
}
