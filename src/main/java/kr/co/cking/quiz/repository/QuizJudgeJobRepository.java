package kr.co.cking.quiz.repository;

import jakarta.persistence.LockModeType;
import kr.co.cking.quiz.domain.QuizJudgeFailure;
import kr.co.cking.quiz.domain.QuizJudgeJob;
import kr.co.cking.quiz.domain.QuizJudgeJobStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.Optional;

/** 상태 전이는 현재 상태를 조건으로 거는 UPDATE나 행 잠금으로만 한다. 바뀐 행 수가 0이면 전이하지 않은 것이다. */
public interface QuizJudgeJobRepository extends JpaRepository<QuizJudgeJob, Long> {

    Optional<QuizJudgeJob> findByCreatorIdAndRequestId(Long creatorId, String requestId);

    boolean existsByCreatorIdAndStatusIn(Long creatorId, Collection<QuizJudgeJobStatus> statuses);

    /** 잠금 순서(Quiz → series → Job)를 지키도록 Job을 잠그기 전에 잠글 대상의 ID만 읽는다. */
    @Query("select new kr.co.cking.quiz.repository.QuizJudgeJobKeys(j.jobId, j.quizId, j.revisionId, j.seriesId) "
            + "from QuizJudgeJob j where j.jobId = :jobId")
    Optional<QuizJudgeJobKeys> findKeys(@Param("jobId") Long jobId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select j from QuizJudgeJob j where j.jobId = :jobId")
    Optional<QuizJudgeJob> findByIdForUpdate(@Param("jobId") Long jobId);

    /** PENDING → RUNNING. 여러 Worker가 같은 Job을 받아도 한 Worker만 성공한다. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update QuizJudgeJob j
               set j.status = :running, j.startedAt = :now, j.updatedAt = :now
             where j.jobId = :jobId and j.status = :pending
            """)
    int claim(@Param("jobId") Long jobId, @Param("pending") QuizJudgeJobStatus pending,
              @Param("running") QuizJudgeJobStatus running, @Param("now") Instant now);

    /** PENDING일 때만 FAILED로 끝낸다. 제출 거절에 쓴다. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update QuizJudgeJob j
               set j.status = :failed, j.failureCode = :failure, j.completedAt = :now, j.updatedAt = :now
             where j.jobId = :jobId and j.status = :pending
            """)
    int failPending(@Param("jobId") Long jobId, @Param("pending") QuizJudgeJobStatus pending,
                    @Param("failed") QuizJudgeJobStatus failed, @Param("failure") QuizJudgeFailure failure,
                    @Param("now") Instant now);

    /** Creator의 Job 중 {@code before}보다 먼저 접수돼 아직 PENDING인 Job을 FAILED로 끝낸다. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update QuizJudgeJob j
               set j.status = :failed, j.failureCode = :failure, j.completedAt = :now, j.updatedAt = :now
             where j.creatorId = :creatorId and j.status = :pending and j.requestedAt < :before
            """)
    int expirePending(@Param("creatorId") Long creatorId, @Param("before") Instant before,
                      @Param("pending") QuizJudgeJobStatus pending, @Param("failed") QuizJudgeJobStatus failed,
                      @Param("failure") QuizJudgeFailure failure, @Param("now") Instant now);

    /** Creator의 Job 중 {@code before}보다 먼저 시작해 아직 RUNNING인 Job을 UNKNOWN으로 끝낸다. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update QuizJudgeJob j
               set j.status = :unknown, j.failureCode = :failure, j.completedAt = :now, j.updatedAt = :now
             where j.creatorId = :creatorId and j.status = :running and j.startedAt < :before
            """)
    int expireRunning(@Param("creatorId") Long creatorId, @Param("before") Instant before,
                      @Param("running") QuizJudgeJobStatus running, @Param("unknown") QuizJudgeJobStatus unknown,
                      @Param("failure") QuizJudgeFailure failure, @Param("now") Instant now);
}
