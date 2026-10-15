package kr.co.cking.member.application;

import kr.co.cking.common.storage.ObjectStorage;
import kr.co.cking.member.repository.MemberProfileImageRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** DELETE_PENDING 개인 프로필 이미지의 Object Storage 객체와 기록을 제거한다. */
@Component
@RequiredArgsConstructor
public class MemberProfileImageStorageCleaner {

    private final ObjectStorage objectStorage;
    private final MemberProfileImageRepository profileImageRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void delete(String objectKey) {
        objectStorage.delete(objectKey);
        profileImageRepository.deleteDeletePending(objectKey);
    }
}
