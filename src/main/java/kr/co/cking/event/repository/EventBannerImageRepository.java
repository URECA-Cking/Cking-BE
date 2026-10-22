package kr.co.cking.event.repository;

import java.time.Instant;
import java.util.List;
import kr.co.cking.event.domain.EventBannerImage;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/** Event 배너 업로드 기록의 상태 전이를 조건부 UPDATE로 처리해, 같은 키를 두 번 연결하거나 지우지 않게 한다. */
public interface EventBannerImageRepository extends JpaRepository<EventBannerImage, Long> {
    @Transactional @Modifying
    @Query("update EventBannerImage i set i.status = kr.co.cking.event.domain.EventBannerImageStatus.UPLOADED, i.statusChangedAt = :now where i.objectKey = :key and i.status = kr.co.cking.event.domain.EventBannerImageStatus.UPLOADING")
    int markUploaded(@Param("key") String key, @Param("now") Instant now);

    /** 본인이 올린 업로드 완료 이미지만 연결한다. 다른 Creator의 키, 이미 연결·삭제 대기인 키는 0건이다. */
    @Transactional @Modifying
    @Query("update EventBannerImage i set i.status = kr.co.cking.event.domain.EventBannerImageStatus.LINKED, i.statusChangedAt = :now where i.objectKey = :key and i.creatorId = :creatorId and i.status = kr.co.cking.event.domain.EventBannerImageStatus.UPLOADED")
    int link(@Param("key") String key, @Param("creatorId") Long creatorId, @Param("now") Instant now);

    @Transactional @Modifying
    @Query("update EventBannerImage i set i.status = kr.co.cking.event.domain.EventBannerImageStatus.DELETE_PENDING, i.statusChangedAt = :now where i.objectKey = :key and i.creatorId = :creatorId and i.status = kr.co.cking.event.domain.EventBannerImageStatus.LINKED")
    int markDeletePending(@Param("key") String key, @Param("creatorId") Long creatorId, @Param("now") Instant now);

    @Transactional @Modifying
    @Query("update EventBannerImage i set i.status = kr.co.cking.event.domain.EventBannerImageStatus.DELETE_PENDING, i.statusChangedAt = :now where i.status in (kr.co.cking.event.domain.EventBannerImageStatus.UPLOADING, kr.co.cking.event.domain.EventBannerImageStatus.UPLOADED) and i.createdAt < :cutoff")
    int claimStaleUnlinked(@Param("cutoff") Instant cutoff, @Param("now") Instant now);

    @Query("select i from EventBannerImage i where i.status = kr.co.cking.event.domain.EventBannerImageStatus.DELETE_PENDING and i.statusChangedAt < :before order by i.statusChangedAt asc, i.bannerImageId asc")
    List<EventBannerImage> findDeletePending(@Param("before") Instant before, Pageable pageable);

    @Transactional @Modifying
    @Query("delete from EventBannerImage i where i.objectKey = :key and i.status = kr.co.cking.event.domain.EventBannerImageStatus.DELETE_PENDING")
    int deleteDeletePending(@Param("key") String key);

    @Transactional @Modifying
    @Query("update EventBannerImage i set i.statusChangedAt = :now where i.objectKey = :key and i.status = kr.co.cking.event.domain.EventBannerImageStatus.DELETE_PENDING")
    int postponeDeletePending(@Param("key") String key, @Param("now") Instant now);
}
