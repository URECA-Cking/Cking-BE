package kr.co.cking.post.repository;

import jakarta.persistence.LockModeType;
import kr.co.cking.post.domain.CreatorPostComment;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

    /** 게시글 삭제 전에 같은 Transaction에서 댓글을 지운다. */
    @Modifying
    @Query("delete from CreatorPostComment c where c.postId = :postId")
    int deleteByPostId(@Param("postId") Long postId);
}
