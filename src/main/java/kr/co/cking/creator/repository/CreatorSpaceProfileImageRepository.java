package kr.co.cking.creator.repository;

import java.time.Instant;
import java.util.List;
import kr.co.cking.creator.domain.CreatorSpaceProfileImage;
import kr.co.cking.creator.domain.CreatorSpaceProfileImageStatus;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.annotation.Transactional;

public interface CreatorSpaceProfileImageRepository extends JpaRepository<CreatorSpaceProfileImage, Long> {
    @Transactional @Modifying
    @Query("update CreatorSpaceProfileImage i set i.status = kr.co.cking.creator.domain.CreatorSpaceProfileImageStatus.UPLOADED, i.statusChangedAt = :now where i.objectKey = :key and i.status = kr.co.cking.creator.domain.CreatorSpaceProfileImageStatus.UPLOADING")
    int markUploaded(@Param("key") String key, @Param("now") Instant now);
    @Transactional @Modifying
    @Query("update CreatorSpaceProfileImage i set i.status = kr.co.cking.creator.domain.CreatorSpaceProfileImageStatus.LINKED, i.statusChangedAt = :now where i.objectKey = :key and i.creatorId = :creatorId and i.status = kr.co.cking.creator.domain.CreatorSpaceProfileImageStatus.UPLOADED")
    int link(@Param("key") String key, @Param("creatorId") Long creatorId, @Param("now") Instant now);
    @Transactional @Modifying
    @Query("update CreatorSpaceProfileImage i set i.status = kr.co.cking.creator.domain.CreatorSpaceProfileImageStatus.DELETE_PENDING, i.statusChangedAt = :now where i.objectKey = :key and i.creatorId = :creatorId and i.status = kr.co.cking.creator.domain.CreatorSpaceProfileImageStatus.LINKED")
    int markDeletePending(@Param("key") String key, @Param("creatorId") Long creatorId, @Param("now") Instant now);

    @Transactional @Modifying
    @Query("update CreatorSpaceProfileImage i set i.status = kr.co.cking.creator.domain.CreatorSpaceProfileImageStatus.DELETE_PENDING, i.statusChangedAt = :now where i.status in (kr.co.cking.creator.domain.CreatorSpaceProfileImageStatus.UPLOADING, kr.co.cking.creator.domain.CreatorSpaceProfileImageStatus.UPLOADED) and i.createdAt < :cutoff")
    int claimStaleUnlinked(@Param("cutoff") Instant cutoff, @Param("now") Instant now);

    @Query("select i from CreatorSpaceProfileImage i where i.status = kr.co.cking.creator.domain.CreatorSpaceProfileImageStatus.DELETE_PENDING and i.statusChangedAt < :before order by i.statusChangedAt asc, i.profileImageId asc")
    List<CreatorSpaceProfileImage> findDeletePending(@Param("before") Instant before, Pageable pageable);

    @Transactional @Modifying
    @Query("delete from CreatorSpaceProfileImage i where i.objectKey = :key and i.status = kr.co.cking.creator.domain.CreatorSpaceProfileImageStatus.DELETE_PENDING")
    int deleteDeletePending(@Param("key") String key);

    @Transactional @Modifying
    @Query("update CreatorSpaceProfileImage i set i.statusChangedAt = :now where i.objectKey = :key and i.status = kr.co.cking.creator.domain.CreatorSpaceProfileImageStatus.DELETE_PENDING")
    int postponeDeletePending(@Param("key") String key, @Param("now") Instant now);
}
