package kr.co.cking.post.repository;

import jakarta.persistence.LockModeType;
import kr.co.cking.post.domain.CreatorPost;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface CreatorPostRepository extends JpaRepository<CreatorPost, Long> {

    /** 같은 게시글의 수정·삭제가 동시에 이미지 연결을 바꾸지 않도록 게시글 행을 잠근다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from CreatorPost p where p.postId = :postId")
    Optional<CreatorPost> findByIdForUpdate(@Param("postId") Long postId);

    /**
     * 댓글 작성·수정·삭제가 게시글 수정·삭제({@link #findByIdForUpdate})와 겹치지 않도록 게시글 행을 공유 잠금으로 읽는다.
     * 공유 잠금끼리는 충돌하지 않아 같은 게시글의 댓글 쓰기는 서로 기다리지 않는다.
     */
    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("select p from CreatorPost p where p.postId = :postId")
    Optional<CreatorPost> findByIdForShare(@Param("postId") Long postId);

    @Query("select p from CreatorPost p where p.creatorId = :creatorId order by p.createdAt desc, p.postId desc")
    Page<CreatorPost> findByCreatorIdLatestFirst(@Param("creatorId") Long creatorId, Pageable pageable);
}
