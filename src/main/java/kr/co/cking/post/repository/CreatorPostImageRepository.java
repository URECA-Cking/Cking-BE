package kr.co.cking.post.repository;

import kr.co.cking.post.domain.CreatorPostImage;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

/**
 * 게시글 이미지 업로드 기록. 상태·연결 변경은 모두 조건부 UPDATE이며, 호출자는 변경된 행 수로 성공 여부를 판단한다.
 */
public interface CreatorPostImageRepository extends JpaRepository<CreatorPostImage, Long> {

    /** 저장소 put 성공 후 UPLOADING 기록을 UPLOADED로 바꾼다. 업로드 흐름은 긴 Transaction 밖에서 호출한다. */
    @Transactional
    @Modifying
    @Query("""
            update CreatorPostImage i
               set i.status = kr.co.cking.post.domain.PostImageStatus.UPLOADED, i.statusChangedAt = :now
             where i.objectKey = :objectKey
               and i.status = kr.co.cking.post.domain.PostImageStatus.UPLOADING
            """)
    int markUploaded(@Param("objectKey") String objectKey, @Param("now") Instant now);

    /**
     * 본인이 올렸고 아직 어떤 게시글에도 연결되지 않은 UPLOADED 이미지만 게시글에 연결한다.
     * 남의 key, 이미 연결된 key, 정리 대상이 된 key는 변경되지 않으므로 반환 값이 요청 key 수보다 작다.
     */
    @Modifying
    @Query("""
            update CreatorPostImage i
               set i.postId = :postId, i.statusChangedAt = :now
             where i.objectKey in :objectKeys
               and i.creatorId = :creatorId
               and i.status = kr.co.cking.post.domain.PostImageStatus.UPLOADED
               and i.postId is null
            """)
    int linkToPost(
            @Param("objectKeys") Collection<String> objectKeys,
            @Param("creatorId") Long creatorId,
            @Param("postId") Long postId,
            @Param("now") Instant now
    );

    @Modifying
    @Query("""
            update CreatorPostImage i
               set i.displayOrder = :displayOrder
             where i.objectKey = :objectKey and i.postId = :postId
            """)
    int updateDisplayOrder(
            @Param("objectKey") String objectKey,
            @Param("postId") Long postId,
            @Param("displayOrder") int displayOrder
    );

    /** 게시글에서 빠진 이미지를 연결 해제하고 저장소 삭제 대기로 바꾼다. */
    @Modifying
    @Query("""
            update CreatorPostImage i
               set i.status = kr.co.cking.post.domain.PostImageStatus.DELETE_PENDING,
                   i.postId = null, i.displayOrder = null, i.statusChangedAt = :now
             where i.postId = :postId and i.objectKey in :objectKeys
            """)
    int releaseFromPost(
            @Param("postId") Long postId,
            @Param("objectKeys") Collection<String> objectKeys,
            @Param("now") Instant now
    );

    List<CreatorPostImage> findByPostIdOrderByDisplayOrderAsc(Long postId);

    List<CreatorPostImage> findByPostIdInOrderByPostIdAscDisplayOrderAsc(Collection<Long> postIds);

    /**
     * 기준 시각 전에 올라와 게시글에 연결되지 않은 UPLOADING·UPLOADED 기록을 삭제 대기로 선점한다.
     * 게시글 연결(linkToPost)과 같은 행을 두고 경합해도 {@code postId is null} 조건 때문에 한쪽만 성공한다.
     */
    @Transactional
    @Modifying
    @Query("""
            update CreatorPostImage i
               set i.status = kr.co.cking.post.domain.PostImageStatus.DELETE_PENDING, i.statusChangedAt = :now
             where i.postId is null
               and i.status in (kr.co.cking.post.domain.PostImageStatus.UPLOADING,
                                kr.co.cking.post.domain.PostImageStatus.UPLOADED)
               and i.createdAt < :cutoff
            """)
    int claimStaleUnlinked(@Param("cutoff") Instant cutoff, @Param("now") Instant now);

    @Query("""
            select i from CreatorPostImage i
             where i.status = kr.co.cking.post.domain.PostImageStatus.DELETE_PENDING
               and i.statusChangedAt < :changedBefore
             order by i.statusChangedAt asc, i.imageId asc
            """)
    List<CreatorPostImage> findDeletePending(@Param("changedBefore") Instant changedBefore, Pageable pageable);

    /** 저장소 삭제가 끝난 DELETE_PENDING 기록을 지운다. 다른 상태로 바뀐 기록은 지우지 않는다. */
    @Transactional
    @Modifying
    @Query("""
            delete from CreatorPostImage i
             where i.objectKey = :objectKey
               and i.status = kr.co.cking.post.domain.PostImageStatus.DELETE_PENDING
            """)
    int deleteDeletePending(@Param("objectKey") String objectKey);
}
