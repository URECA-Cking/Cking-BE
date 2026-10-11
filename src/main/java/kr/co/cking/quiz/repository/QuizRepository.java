package kr.co.cking.quiz.repository;

import jakarta.persistence.LockModeType;
import kr.co.cking.quiz.domain.Quiz;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface QuizRepository extends JpaRepository<Quiz, Long> {

    /** 같은 Quiz의 승인·거절·게시·게시 취소를 직렬화한다. 모든 변경 명령은 이 잠금부터 잡는다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select q from Quiz q where q.quizId = :quizId")
    Optional<Quiz> findByIdForUpdate(@Param("quizId") Long quizId);

    @Query("select q from Quiz q where q.creatorId = :creatorId order by q.createdAt desc, q.quizId desc")
    Page<Quiz> findByCreatorIdLatestFirst(@Param("creatorId") Long creatorId, Pageable pageable);

    @Query("""
            select q from Quiz q
            where q.creatorId = :creatorId
              and q.publicationStatus = kr.co.cking.quiz.domain.QuizPublicationStatus.PUBLISHED
            order by q.publishedAt desc, q.quizId desc
            """)
    Page<Quiz> findPublishedByCreatorId(@Param("creatorId") Long creatorId, Pageable pageable);
}
