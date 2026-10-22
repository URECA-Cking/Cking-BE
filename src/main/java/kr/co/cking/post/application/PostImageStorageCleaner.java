package kr.co.cking.post.application;

import kr.co.cking.common.storage.ObjectStorage;
import kr.co.cking.post.repository.CreatorPostImageRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * DELETE_PENDING 이미지 한 장을 저장소에서 지우고 기록을 삭제한다.
 *
 * <p>AFTER_COMMIT 리스너에서도 호출하므로 새 Transaction에서 실행한다. 저장소 delete는 없는 key도 성공하므로
 * 여러 번 실행돼도 안전하다. 저장소 삭제가 실패하면 기록이 남아 정리 스케줄러가 다시 시도한다.
 */
@Component
@RequiredArgsConstructor
public class PostImageStorageCleaner {

    private final ObjectStorage objectStorage;
    private final CreatorPostImageRepository imageRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void delete(String objectKey) {
        objectStorage.delete(objectKey);
        imageRepository.deleteDeletePending(objectKey);
    }
}
