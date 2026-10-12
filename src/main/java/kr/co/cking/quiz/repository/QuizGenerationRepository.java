package kr.co.cking.quiz.repository;

import jakarta.persistence.LockModeType;
import kr.co.cking.quiz.domain.QuizGeneration;
import kr.co.cking.quiz.domain.QuizGenerationFailure;
import kr.co.cking.quiz.domain.QuizGenerationStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

/** 상태 전이는 현재 상태를 조건으로 거는 UPDATE나 행 잠금으로만 한다. 바뀐 행 수가 0이면 전이하지 않은 것이다. */
public interface QuizGenerationRepository extends JpaRepository<QuizGeneration, Long> {

    Optional<QuizGeneration> findByCreatorIdAndRequestId(Long creatorId, String requestId);

    /** 결과 저장과 상태 정리가 겹치지 않도록 작업 행을 잠근다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select g from QuizGeneration g where g.generationId = :generationId")
    Optional<QuizGeneration> findByIdForUpdate(@Param("generationId") Long generationId);

    /** PENDING → RUNNING. 여러 Worker가 같은 작업을 받아도 한 Worker만 성공한다. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update QuizGeneration g
               set g.status = :running, g.startedAt = :now, g.updatedAt = :now
             where g.generationId = :generationId and g.status = :pending
            """)
    int claim(@Param("generationId") Long generationId, @Param("pending") QuizGenerationStatus pending,
              @Param("running") QuizGenerationStatus running, @Param("now") Instant now);

    /** {@code from} 상태일 때만 끝 상태(FAILED·UNKNOWN)로 바꾼다. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update QuizGeneration g
               set g.status = :to, g.failureCode = :failure, g.completedAt = :now, g.updatedAt = :now
             where g.generationId = :generationId and g.status = :from
            """)
    int finish(@Param("generationId") Long generationId, @Param("from") QuizGenerationStatus from,
               @Param("to") QuizGenerationStatus to, @Param("failure") QuizGenerationFailure failure,
               @Param("now") Instant now);

    /** Creator의 작업 중 {@code before}보다 먼저 접수돼 아직 PENDING인 작업을 FAILED로 끝낸다. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update QuizGeneration g
               set g.status = :failed, g.failureCode = :failure, g.completedAt = :now, g.updatedAt = :now
             where g.creatorId = :creatorId and g.status = :pending and g.requestedAt < :before
            """)
    int expirePending(@Param("creatorId") Long creatorId, @Param("before") Instant before,
                      @Param("pending") QuizGenerationStatus pending, @Param("failed") QuizGenerationStatus failed,
                      @Param("failure") QuizGenerationFailure failure, @Param("now") Instant now);

    /** Creator의 작업 중 {@code before}보다 먼저 시작해 아직 RUNNING인 작업을 UNKNOWN으로 끝낸다. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update QuizGeneration g
               set g.status = :unknown, g.failureCode = :failure, g.completedAt = :now, g.updatedAt = :now
             where g.creatorId = :creatorId and g.status = :running and g.startedAt < :before
            """)
    int expireRunning(@Param("creatorId") Long creatorId, @Param("before") Instant before,
                      @Param("running") QuizGenerationStatus running, @Param("unknown") QuizGenerationStatus unknown,
                      @Param("failure") QuizGenerationFailure failure, @Param("now") Instant now);
}
