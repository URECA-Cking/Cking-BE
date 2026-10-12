package kr.co.cking.member.application;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** 이전 사진 삭제 실패는 DB의 DELETE_PENDING 기록을 남겨 정리 scheduler가 회수하게 한다. */
@Component
@RequiredArgsConstructor
@Slf4j
public class MemberProfileImageDeletionListener {

    private final MemberProfileImageStorageCleaner storageCleaner;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void deleteAfterCommit(MemberProfileImageDeletionRequestedEvent event) {
        try {
            storageCleaner.delete(event.objectKey());
        } catch (RuntimeException exception) {
            log.warn("교체된 프로필 이미지를 즉시 삭제하지 못했습니다. scheduler가 재시도합니다. objectKey={}",
                    event.objectKey(), exception);
        }
    }
}
