package kr.co.cking.post.repository;

import jakarta.persistence.LockModeType;
import kr.co.cking.post.domain.CommentFilterStatus;
import kr.co.cking.post.domain.CreatorPostComment;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface CreatorPostCommentRepository extends JpaRepository<CreatorPostComment, Long> {

    /**
     * 같은 댓글의 수정·삭제가 겹치지 않도록 댓글 행을 쓰기 잠금으로 읽는다. 먼저 삭제가 Commit되면 빈 결과가 되어
     * 사라진 행을 변경하는 예외(500) 대신 404로 응답할 수 있다. 호출 전에 게시글 공유 잠금을 먼저 잡는다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from CreatorPostComment c where c.commentId = :commentId")
    Optional<CreatorPostComment> findByIdForUpdate(@Param("commentId") Long commentId);

    @Query("""
            select c from CreatorPostComment c
             where c.postId = :postId
             order by c.createdAt asc, c.commentId asc
            """)
    Page<CreatorPostComment> findByPostIdOldestFirst(@Param("postId") Long postId, Pageable pageable);

    /**
     * 다시 필터에 제출할 댓글. 재시도한 적 없는 댓글({@code filterNextAttemptAt}이 NULL)은 마지막 수정·작성 뒤
     * {@code pendingBefore}가 지났을 때부터, 재시도한 댓글은 다음 시도 시각({@code now})이 되었을 때부터 대상이다.
     * 시도 횟수가 상한({@code maxAttempts})에 닿은 댓글은 뺀다. 오래 기다린 순으로 가져온다.
     */
    @Query("""
            select new kr.co.cking.post.repository.CommentFilterRetryCandidate(
                       c.commentId, c.filterAttempts, c.updatedAt, c.filterNextAttemptAt)
              from CreatorPostComment c
             where c.filterStatus in :statuses
               and c.filterAttempts < :maxAttempts
               and ((c.filterNextAttemptAt is null and c.updatedAt <= :pendingBefore)
                    or c.filterNextAttemptAt <= :now)
             order by coalesce(c.filterNextAttemptAt, c.updatedAt) asc, c.commentId asc
            """)
    List<CommentFilterRetryCandidate> findRetryCandidates(
            @Param("statuses") Collection<CommentFilterStatus> statuses,
            @Param("maxAttempts") int maxAttempts,
            @Param("pendingBefore") Instant pendingBefore,
            @Param("now") Instant now,
            Pageable pageable);

    /**
     * 재제출을 확보한다. 조회한 뒤 상태, 시도 횟수, 수정 시각이 바뀌었으면(판정 완료, 본문 수정) 0행이 되어
     * 제출하지 않는다. 시도 횟수는 수정으로 0이 되므로 횟수만으로는 조회 때도 0이었던 댓글의 수정을 가리지 못해
     * 수정 시각도 비교한다.
     *
     * @return 확보했으면 1
     */
    @Modifying
    @Query("""
            update CreatorPostComment c
               set c.filterAttempts = c.filterAttempts + 1,
                   c.filterNextAttemptAt = :nextAttemptAt
             where c.commentId = :commentId
               and c.filterStatus in :statuses
               and c.filterAttempts = :expectedAttempts
               and c.updatedAt = :expectedUpdatedAt
            """)
    int claimForRetry(
            @Param("commentId") Long commentId,
            @Param("statuses") Collection<CommentFilterStatus> statuses,
            @Param("expectedAttempts") int expectedAttempts,
            @Param("expectedUpdatedAt") Instant expectedUpdatedAt,
            @Param("nextAttemptAt") Instant nextAttemptAt);

    /**
     * 확보한 재제출을 되돌린다. 확보한 뒤 필터 Executor 큐가 가득 차 제출하지 못했을 때, 필터가 한 번도 실행되지
     * 않았는데 시도 횟수만 소진되지 않도록 횟수와 다음 시도 시각을 확보 전 값으로 복원한다. 그사이 판정이 끝났거나
     * 본문이 수정되어 횟수가 바뀌었으면 0행이며 되돌리지 않는다.
     *
     * @return 되돌렸으면 1
     */
    @Modifying
    @Query("""
            update CreatorPostComment c
               set c.filterAttempts = c.filterAttempts - 1,
                   c.filterNextAttemptAt = :previousNextAttemptAt
             where c.commentId = :commentId
               and c.filterStatus in :statuses
               and c.filterAttempts = :claimedAttempts
            """)
    int releaseRetryClaim(
            @Param("commentId") Long commentId,
            @Param("statuses") Collection<CommentFilterStatus> statuses,
            @Param("claimedAttempts") int claimedAttempts,
            @Param("previousNextAttemptAt") Instant previousNextAttemptAt);

    /** 게시글 삭제 전에 같은 Transaction에서 댓글을 지운다. */
    @Modifying
    @Query("delete from CreatorPostComment c where c.postId = :postId")
    int deleteByPostId(@Param("postId") Long postId);
}
