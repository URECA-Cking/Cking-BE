package kr.co.cking.upload.repository;

import java.time.Instant;
import java.util.Optional;
import kr.co.cking.upload.domain.ImageUpload;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/** 결과 큐는 같은 메시지를 두 번 줄 수 있어 PENDING일 때만 바꾼다. */
public interface ImageUploadRepository extends JpaRepository<ImageUpload, String> {

    Optional<ImageUpload> findByObjectKey(String objectKey);

    @Transactional
    @Modifying
    @Query("update ImageUpload u set u.status = kr.co.cking.upload.domain.ImageUploadStatus.READY, u.completedAt = :now where u.objectKey = :key and u.status = kr.co.cking.upload.domain.ImageUploadStatus.PENDING")
    int markReady(@Param("key") String key, @Param("now") Instant now);

    @Transactional
    @Modifying
    @Query("update ImageUpload u set u.status = kr.co.cking.upload.domain.ImageUploadStatus.FAILED, u.failureReason = :reason, u.completedAt = :now where u.objectKey = :key and u.status = kr.co.cking.upload.domain.ImageUploadStatus.PENDING")
    int markFailed(@Param("key") String key, @Param("reason") String reason, @Param("now") Instant now);
}
