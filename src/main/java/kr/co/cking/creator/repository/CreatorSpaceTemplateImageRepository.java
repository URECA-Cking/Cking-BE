package kr.co.cking.creator.repository;

import java.time.Instant;
import java.util.List;
import kr.co.cking.creator.domain.CreatorSpaceTemplateImage;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface CreatorSpaceTemplateImageRepository extends JpaRepository<CreatorSpaceTemplateImage, Long> {
    /**
     * 이 이미지({@code i})의 key가 어느 템플릿·Space의 URL에든 들어 있는 조건이다. 템플릿 값은 Creator 승인 시점에 Space로
     * 복사되고 기본 이미지·기본 배너 복귀에도 쓰이므로 템플릿과 Space의 URL 컬럼 여섯 곳을 모두 본다.
     * URL 전체가 아니라 key 포함 여부로 보는 것은 CDN 주소가 바뀌어도 참조를 놓쳐 쓰는 이미지를 지우는 일을 막기 위해서다.
     * key는 UUID 파일명이라 다른 이미지의 key에 포함되지 않는다.
     */
    String REFERENCED = "(exists (select 1 from CreatorSpaceTemplate t"
            + " where locate(i.objectKey, t.profileImageUrl) > 0 or locate(i.objectKey, t.bannerImageUrl) > 0)"
            + " or exists (select 1 from CreatorSpace s"
            + " where locate(i.objectKey, s.profileImageUrl) > 0 or locate(i.objectKey, s.defaultProfileImageUrl) > 0"
            + " or locate(i.objectKey, s.bannerImageUrl) > 0 or locate(i.objectKey, s.defaultBannerImageUrl) > 0))";

    @Transactional @Modifying
    @Query("update CreatorSpaceTemplateImage i set i.status = kr.co.cking.creator.domain.CreatorSpaceTemplateImageStatus.UPLOADED, i.statusChangedAt = :now where i.objectKey = :key and i.status = kr.co.cking.creator.domain.CreatorSpaceTemplateImageStatus.UPLOADING")
    int markUploaded(@Param("key") String key, @Param("now") Instant now);

    /**
     * 템플릿 저장이 이 이미지를 쓰기 직전에 호출한다. 정리 대상으로 선점됐지만 아직 삭제가 확정되지 않은 이미지를 포함해
     * 업로드 완료 상태로 되돌리고(1건), 삭제가 확정됐거나 완료 전인 이미지는 건드리지 않는다(0건). 이 UPDATE가 잡은 행 잠금은
     * 호출한 트랜잭션(템플릿 저장)이 끝날 때까지 유지되므로, 같은 행을 삭제 확정하려는 {@link #markDeleting}은 그동안 기다렸다가
     * 이미 업로드 완료 상태가 된 것을 보고 실패한다.
     */
    @Transactional @Modifying
    @Query("update CreatorSpaceTemplateImage i set i.status = kr.co.cking.creator.domain.CreatorSpaceTemplateImageStatus.UPLOADED, i.statusChangedAt = :now where i.objectKey = :key and i.status in (kr.co.cking.creator.domain.CreatorSpaceTemplateImageStatus.UPLOADED, kr.co.cking.creator.domain.CreatorSpaceTemplateImageStatus.DELETE_PENDING)")
    int pin(@Param("key") String key, @Param("now") Instant now);

    boolean existsByObjectKey(String objectKey);

    /** 올린 지 {@code cutoff}가 지났고 어디에서도 참조되지 않는 업로드를 삭제 대기로 선점한다. 참조되는 이미지는 건드리지 않는다. */
    @Transactional @Modifying
    @Query("update CreatorSpaceTemplateImage i set i.status = kr.co.cking.creator.domain.CreatorSpaceTemplateImageStatus.DELETE_PENDING, i.statusChangedAt = :now where i.status in (kr.co.cking.creator.domain.CreatorSpaceTemplateImageStatus.UPLOADING, kr.co.cking.creator.domain.CreatorSpaceTemplateImageStatus.UPLOADED) and i.createdAt < :cutoff and not " + REFERENCED)
    int claimStaleUnreferenced(@Param("cutoff") Instant cutoff, @Param("now") Instant now);

    /** 삭제 대기 또는 삭제 확정 상태로 {@code before} 이전부터 머문 기록을 오래된 순으로 읽는다. 삭제 확정 상태는 저장소 삭제 재시도 대상이다. */
    @Query("select i from CreatorSpaceTemplateImage i where i.status in (kr.co.cking.creator.domain.CreatorSpaceTemplateImageStatus.DELETE_PENDING, kr.co.cking.creator.domain.CreatorSpaceTemplateImageStatus.DELETING) and i.statusChangedAt < :before order by i.statusChangedAt asc, i.templateImageId asc")
    List<CreatorSpaceTemplateImage> findPendingDeletion(@Param("before") Instant before, Pageable pageable);

    /** 삭제 확정 직전에 다시 참조를 확인한다. 선점한 뒤 쓰이게 된 이미지는 업로드 상태로 되돌리고 1을 돌려준다. */
    @Transactional @Modifying
    @Query("update CreatorSpaceTemplateImage i set i.status = kr.co.cking.creator.domain.CreatorSpaceTemplateImageStatus.UPLOADED, i.statusChangedAt = :now where i.objectKey = :key and i.status = kr.co.cking.creator.domain.CreatorSpaceTemplateImageStatus.DELETE_PENDING and " + REFERENCED)
    int restoreIfReferenced(@Param("key") String key, @Param("now") Instant now);

    /**
     * 저장소 객체를 지우기로 확정한다. 삭제 대기 상태일 때만 1건이며, 그 사이 템플릿 저장이 {@link #pin}으로 이미지를 쓰기
     * 시작했다면 0건이므로 호출한 쪽은 저장소를 지우지 않아야 한다.
     */
    @Transactional @Modifying
    @Query("update CreatorSpaceTemplateImage i set i.status = kr.co.cking.creator.domain.CreatorSpaceTemplateImageStatus.DELETING, i.statusChangedAt = :now where i.objectKey = :key and i.status = kr.co.cking.creator.domain.CreatorSpaceTemplateImageStatus.DELETE_PENDING")
    int markDeleting(@Param("key") String key, @Param("now") Instant now);

    /** 저장소 객체를 지운 기록을 남긴다. 행을 지우면 옛 URL을 다시 붙여 넣는 저장을 거절할 수 없다. */
    @Transactional @Modifying
    @Query("update CreatorSpaceTemplateImage i set i.status = kr.co.cking.creator.domain.CreatorSpaceTemplateImageStatus.DELETED, i.statusChangedAt = :now where i.objectKey = :key and i.status = kr.co.cking.creator.domain.CreatorSpaceTemplateImageStatus.DELETING")
    int markDeleted(@Param("key") String key, @Param("now") Instant now);

    /** 저장소 삭제에 실패한 기록의 재시도 시각을 미뤄 대기열 뒤로 보낸다. 삭제 확정 상태는 그대로 둔다. */
    @Transactional @Modifying
    @Query("update CreatorSpaceTemplateImage i set i.statusChangedAt = :now where i.objectKey = :key and i.status in (kr.co.cking.creator.domain.CreatorSpaceTemplateImageStatus.DELETE_PENDING, kr.co.cking.creator.domain.CreatorSpaceTemplateImageStatus.DELETING)")
    int postponeDeletion(@Param("key") String key, @Param("now") Instant now);
}
