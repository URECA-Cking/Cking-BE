package kr.co.cking.quiz.repository;

import jakarta.persistence.LockModeType;
import kr.co.cking.quiz.domain.QuizGeneration;
import kr.co.cking.quiz.domain.QuizGenerationFailure;
import kr.co.cking.quiz.domain.QuizGenerationReviewStage;
import kr.co.cking.quiz.domain.QuizGenerationStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.Optional;

/** 상태 전이는 현재 상태를 조건으로 거는 UPDATE나 행 잠금으로만 한다. 바뀐 행 수가 0이면 전이하지 않은 것이다. */
public interface QuizGenerationRepository extends JpaRepository<QuizGeneration, Long> {

    Optional<QuizGeneration> findByCreatorIdAndRequestId(Long creatorId, String requestId);

    /** Creator의 진행 중 작업. {@code active_creator_id} UNIQUE로 PENDING·RUNNING 작업은 하나뿐이다. */
    Optional<QuizGeneration> findFirstByCreatorIdAndStatusInOrderByRequestedAtDesc(
            Long creatorId, Collection<QuizGenerationStatus> statuses);

    /** Quiz를 만든 생성 작업. 성공한 작업 하나가 Quiz 하나를 만든다. */
    Optional<QuizGeneration> findFirstByQuizIdOrderByGenerationIdAsc(Long quizId);

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

    /** {@code from} 상태이고 원문 검토 단계가 {@code stage}일 때만 끝 상태(FAILED·UNKNOWN)로 바꾼다. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update QuizGeneration g
               set g.status = :to, g.failureCode = :failure, g.completedAt = :now, g.updatedAt = :now
             where g.generationId = :generationId and g.status = :from and g.reviewStage = :stage
            """)
    int finishInStage(@Param("generationId") Long generationId, @Param("from") QuizGenerationStatus from,
                      @Param("stage") QuizGenerationReviewStage stage, @Param("to") QuizGenerationStatus to,
                      @Param("failure") QuizGenerationFailure failure, @Param("now") Instant now);

    /** RUNNING·GROUNDING 작업에 Grounding 결과를 저장하고 원문 검토 대기로 바꾼다. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update QuizGeneration g
               set g.reviewStage = :awaitingReview, g.aiContentText = :contentText, g.groundingFacts = :facts,
                   g.groundingModel = :model, g.groundingPromptVersion = :promptVersion, g.updatedAt = :now
             where g.generationId = :generationId and g.status = :running and g.reviewStage = :grounding
            """)
    int awaitReview(@Param("generationId") Long generationId, @Param("running") QuizGenerationStatus running,
                    @Param("grounding") QuizGenerationReviewStage grounding,
                    @Param("awaitingReview") QuizGenerationReviewStage awaitingReview,
                    @Param("contentText") String contentText, @Param("facts") String facts,
                    @Param("model") String model, @Param("promptVersion") String promptVersion,
                    @Param("now") Instant now);

    /** 확정 원문으로 Quiz 생성을 시작한다. 여러 Worker가 같은 작업을 받아도 한 Worker만 성공한다. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update QuizGeneration g
               set g.generationStartedAt = :now, g.updatedAt = :now
             where g.generationId = :generationId and g.status = :running and g.reviewStage = :generating
               and g.generationStartedAt is null
            """)
    int claimGeneration(@Param("generationId") Long generationId, @Param("running") QuizGenerationStatus running,
                        @Param("generating") QuizGenerationReviewStage generating, @Param("now") Instant now);

    /** 아직 시작하지 않은 생성 단계 작업만 FAILED로 끝낸다. Provider를 호출하지 않았으므로 결과가 확정된 실패다. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update QuizGeneration g
               set g.status = :failed, g.failureCode = :failure, g.completedAt = :now, g.updatedAt = :now
             where g.generationId = :generationId and g.status = :running and g.reviewStage = :generating
               and g.generationStartedAt is null
            """)
    int failUnstartedGeneration(@Param("generationId") Long generationId,
                                @Param("running") QuizGenerationStatus running,
                                @Param("generating") QuizGenerationReviewStage generating,
                                @Param("failed") QuizGenerationStatus failed,
                                @Param("failure") QuizGenerationFailure failure, @Param("now") Instant now);

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

    /**
     * Creator의 작업 중 {@code before}보다 먼저 시작해 아직 RUNNING인 기존 흐름·Grounding 단계 작업을 UNKNOWN으로 끝낸다.
     * 원문 검토 대기는 자동으로 끝내지 않고, 생성 단계는 확정·생성 시작 시각으로 따로 정리한다.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update QuizGeneration g
               set g.status = :unknown, g.failureCode = :failure, g.completedAt = :now, g.updatedAt = :now
             where g.creatorId = :creatorId and g.status = :running and g.startedAt < :before
               and (g.reviewStage is null or g.reviewStage = :grounding)
            """)
    int expireRunning(@Param("creatorId") Long creatorId, @Param("before") Instant before,
                      @Param("running") QuizGenerationStatus running,
                      @Param("grounding") QuizGenerationReviewStage grounding,
                      @Param("unknown") QuizGenerationStatus unknown,
                      @Param("failure") QuizGenerationFailure failure, @Param("now") Instant now);

    /** 확정 후 {@code before}까지 생성을 시작하지 않은 작업을 FAILED로 끝낸다. 재시작·dispatch 유실로 남은 작업이다. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update QuizGeneration g
               set g.status = :failed, g.failureCode = :failure, g.completedAt = :now, g.updatedAt = :now
             where g.creatorId = :creatorId and g.status = :running and g.reviewStage = :generating
               and g.generationStartedAt is null and g.contentConfirmedAt < :before
            """)
    int expireUnstartedGeneration(@Param("creatorId") Long creatorId, @Param("before") Instant before,
                                  @Param("running") QuizGenerationStatus running,
                                  @Param("generating") QuizGenerationReviewStage generating,
                                  @Param("failed") QuizGenerationStatus failed,
                                  @Param("failure") QuizGenerationFailure failure, @Param("now") Instant now);

    /** {@code before}보다 먼저 생성을 시작해 아직 끝나지 않은 작업을 UNKNOWN으로 끝낸다. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update QuizGeneration g
               set g.status = :unknown, g.failureCode = :failure, g.completedAt = :now, g.updatedAt = :now
             where g.creatorId = :creatorId and g.status = :running and g.reviewStage = :generating
               and g.generationStartedAt < :before
            """)
    int expireStartedGeneration(@Param("creatorId") Long creatorId, @Param("before") Instant before,
                                @Param("running") QuizGenerationStatus running,
                                @Param("generating") QuizGenerationReviewStage generating,
                                @Param("unknown") QuizGenerationStatus unknown,
                                @Param("failure") QuizGenerationFailure failure, @Param("now") Instant now);
}
