package kr.co.cking.post.repository;

import kr.co.cking.post.domain.CreatorPostLike;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface CreatorPostLikeRepository extends JpaRepository<CreatorPostLike, CreatorPostLike.Key> {

    /**
     * 좋아요가 없을 때만 추가한다. 동시 요청이 같은 쌍을 넣어도 PK 충돌은 무시되어 하나만 남는다.
     *
     * <p>INSERT IGNORE는 FK 위반 같은 다른 오류까지 경고로 바꾸므로, 중복 키만 무시하는 ON DUPLICATE KEY를 쓴다.
     *
     * <p><b>영향 행 수를 반환하지 않는다.</b> Connector/J는 기본(useAffectedRows=false)에서 "찾은 행 수"를 돌려줘서
     * 이미 있는 행에도 1을 반환한다(실제 드라이버로 확인). 새로 추가했는지는 이 결과가 아니라 {@link #findCreatedAt}로
     * 읽은 생성 시각으로 판단한다.
     */
    @Modifying
    @Query(value = """
            INSERT INTO creator_post_like (post_id, member_id, created_at)
            VALUES (:postId, :memberId, :createdAt)
            ON DUPLICATE KEY UPDATE post_id = post_id
            """, nativeQuery = true)
    void insertIfAbsent(
            @Param("postId") Long postId,
            @Param("memberId") Long memberId,
            @Param("createdAt") Instant createdAt
    );

    /** @return 지웠으면 1, 좋아요가 없었으면 0 */
    @Modifying
    @Query("delete from CreatorPostLike l where l.postId = :postId and l.memberId = :memberId")
    int deleteByPostIdAndMemberId(@Param("postId") Long postId, @Param("memberId") Long memberId);

    long countByPostId(Long postId);

    boolean existsByPostIdAndMemberId(Long postId, Long memberId);

    /** 좋아요를 누른 시각. 좋아요가 없으면 빈 값이다. */
    @Query("select l.createdAt from CreatorPostLike l where l.postId = :postId and l.memberId = :memberId")
    Optional<Instant> findCreatedAt(@Param("postId") Long postId, @Param("memberId") Long memberId);

    /** 한 페이지 게시글의 좋아요 수를 한 번에 센다. 좋아요가 없는 게시글은 결과에 없다. */
    @Query("select l.postId as postId, count(l) as total from CreatorPostLike l where l.postId in :postIds group by l.postId")
    List<PostCount> countByPostIds(@Param("postIds") Collection<Long> postIds);

    /** 한 페이지 게시글 중 회원이 좋아요한 게시글 ID. */
    @Query("select l.postId from CreatorPostLike l where l.memberId = :memberId and l.postId in :postIds")
    List<Long> findLikedPostIds(@Param("memberId") Long memberId, @Param("postIds") Collection<Long> postIds);
}
