package kr.co.cking.follow.repository;

import jakarta.persistence.LockModeType;
import kr.co.cking.follow.domain.CreatorFollow;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface CreatorFollowRepository extends JpaRepository<CreatorFollow, Long> {

    /**
     * 팔로우 관계가 없을 때만 추가한다. 동시 요청이 같은 쌍을 넣어도 unique 제약 충돌은 무시되어 하나만 남는다.
     *
     * <p>INSERT IGNORE는 FK 위반 같은 다른 오류까지 경고로 바꾸므로, 중복 키만 무시하는 ON DUPLICATE KEY를 쓴다.
     */
    @Modifying
    @Query(value = """
            INSERT INTO creator_follow (member_id, creator_id, created_at)
            VALUES (:memberId, :creatorId, :createdAt)
            ON DUPLICATE KEY UPDATE follow_id = follow_id
            """, nativeQuery = true)
    int insertIfAbsent(
            @Param("memberId") Long memberId,
            @Param("creatorId") Long creatorId,
            @Param("createdAt") Instant createdAt
    );

    @Modifying
    @Query("delete from CreatorFollow f where f.memberId = :memberId and f.creatorId = :creatorId")
    int deleteByMemberIdAndCreatorId(@Param("memberId") Long memberId, @Param("creatorId") Long creatorId);

    boolean existsByMemberIdAndCreatorId(Long memberId, Long creatorId);

    /** 읽음 처리 중 언팔로우가 관계를 삭제하지 못하도록 현재 팔로우 행을 잠근다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select f from CreatorFollow f where f.memberId = :memberId and f.creatorId = :creatorId")
    Optional<CreatorFollow> findForReadUpdate(@Param("memberId") Long memberId, @Param("creatorId") Long creatorId);

    /** 같은 회원·Creator의 병렬 읽음 요청은 DB 행 잠금으로 직렬화되고 저장 시각은 앞으로만 간다. */
    @Modifying
    @Query(value = """
            UPDATE creator_follow
            SET last_read_at = GREATEST(COALESCE(last_read_at, created_at), UTC_TIMESTAMP(6))
            WHERE member_id = :memberId AND creator_id = :creatorId
            """, nativeQuery = true)
    int markPostsRead(@Param("memberId") Long memberId, @Param("creatorId") Long creatorId);

    @Query("select f.lastReadAt from CreatorFollow f where f.memberId = :memberId and f.creatorId = :creatorId")
    Instant findLastReadAt(@Param("memberId") Long memberId, @Param("creatorId") Long creatorId);

    /** 크리에이터의 팔로워 수. idx_creator_follow_creator로 집계한다. */
    long countByCreatorId(Long creatorId);

    /**
     * 여러 크리에이터의 팔로워 수를 한 번의 그룹 쿼리로 센다. idx_creator_follow_creator를 쓰며, 팔로워가 0명인 크리에이터는 행이 없다.
     */
    @Query("""
            select new kr.co.cking.follow.repository.CreatorFollowerCount(f.creatorId, count(f.followId))
            from CreatorFollow f
            where f.creatorId in :creatorIds
            group by f.creatorId
            """)
    List<CreatorFollowerCount> countFollowersByCreatorIds(@Param("creatorIds") Collection<Long> creatorIds);

    /** 회원이 현재 팔로우 중인 Creator 수를 상태 변경 없이 조회한다. */
    long countByMemberId(Long memberId);

    @Query("select f from CreatorFollow f where f.memberId = :memberId order by f.createdAt desc, f.followId desc")
    Page<CreatorFollow> findByMemberIdLatestFirst(@Param("memberId") Long memberId, Pageable pageable);

    @Query("select f.creatorId from CreatorFollow f where f.memberId = :memberId order by f.creatorId asc")
    List<Long> findCreatorIdsByMemberIdOrderByCreatorIdAsc(@Param("memberId") Long memberId);
}
