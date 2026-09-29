package kr.co.cking.follow.repository;

import kr.co.cking.follow.domain.CreatorFollow;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;

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

    @Query("select f from CreatorFollow f where f.memberId = :memberId order by f.createdAt desc, f.followId desc")
    Page<CreatorFollow> findByMemberIdLatestFirst(@Param("memberId") Long memberId, Pageable pageable);
}
