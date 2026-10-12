package kr.co.cking.member.repository;

import java.time.Instant;
import java.util.List;
import kr.co.cking.member.domain.MemberProfileImage;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/** 개인 프로필 이미지의 소유권 연결과 오래된 미연결 업로드 정리를 위한 저장소다. */
public interface MemberProfileImageRepository extends JpaRepository<MemberProfileImage, Long> {

    @Transactional
    @Modifying
    @Query("""
            update MemberProfileImage i
               set i.status = kr.co.cking.member.domain.MemberProfileImageStatus.UPLOADED,
                   i.statusChangedAt = :now
             where i.objectKey = :objectKey
               and i.status = kr.co.cking.member.domain.MemberProfileImageStatus.UPLOADING
            """)
    int markUploaded(@Param("objectKey") String objectKey, @Param("now") Instant now);

    /** 본인이 올린 미연결 이미지 하나만 현재 프로필에 연결한다. */
    @Modifying
    @Query("""
            update MemberProfileImage i
               set i.status = kr.co.cking.member.domain.MemberProfileImageStatus.LINKED,
                   i.statusChangedAt = :now
             where i.objectKey = :objectKey
               and i.memberId = :memberId
               and i.status = kr.co.cking.member.domain.MemberProfileImageStatus.UPLOADED
            """)
    int linkToProfile(@Param("objectKey") String objectKey, @Param("memberId") Long memberId, @Param("now") Instant now);

    /** 기존 연결 이미지만 삭제 대기로 바꿔 동시 교체에서 현재 사진을 지우지 않는다. */
    @Modifying
    @Query("""
            update MemberProfileImage i
               set i.status = kr.co.cking.member.domain.MemberProfileImageStatus.DELETE_PENDING,
                   i.statusChangedAt = :now
             where i.objectKey = :objectKey
               and i.memberId = :memberId
               and i.status = kr.co.cking.member.domain.MemberProfileImageStatus.LINKED
            """)
    int markDeletePending(@Param("objectKey") String objectKey, @Param("memberId") Long memberId, @Param("now") Instant now);

    /** 24시간 동안 연결되지 않은 업로드를 삭제 대기로 선점한다. */
    @Transactional
    @Modifying
    @Query("""
            update MemberProfileImage i
               set i.status = kr.co.cking.member.domain.MemberProfileImageStatus.DELETE_PENDING,
                   i.statusChangedAt = :now
             where i.status in (kr.co.cking.member.domain.MemberProfileImageStatus.UPLOADING,
                                kr.co.cking.member.domain.MemberProfileImageStatus.UPLOADED)
               and i.createdAt < :cutoff
            """)
    int claimStaleUnlinked(@Param("cutoff") Instant cutoff, @Param("now") Instant now);

    @Query("""
            select i from MemberProfileImage i
             where i.status = kr.co.cking.member.domain.MemberProfileImageStatus.DELETE_PENDING
               and i.statusChangedAt < :changedBefore
             order by i.statusChangedAt asc, i.profileImageId asc
            """)
    List<MemberProfileImage> findDeletePending(@Param("changedBefore") Instant changedBefore, Pageable pageable);

    @Transactional
    @Modifying
    @Query("""
            update MemberProfileImage i
               set i.statusChangedAt = :now
             where i.objectKey = :objectKey
               and i.status = kr.co.cking.member.domain.MemberProfileImageStatus.DELETE_PENDING
            """)
    int postponeDeletePending(@Param("objectKey") String objectKey, @Param("now") Instant now);

    @Transactional
    @Modifying
    @Query("""
            delete from MemberProfileImage i
             where i.objectKey = :objectKey
               and i.status = kr.co.cking.member.domain.MemberProfileImageStatus.DELETE_PENDING
            """)
    int deleteDeletePending(@Param("objectKey") String objectKey);
}
