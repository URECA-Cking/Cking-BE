package kr.co.cking.quiz.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import kr.co.cking.quiz.judge.QuizJudgeExecution;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Objects;

/**
 * 평가 시도(attempt) 하나(이슈 #529). Creator의 requestId 하나에 한 행이며 API의 {@code evaluationId}가 이 ID다.
 * 불변 평가 결과({@link QuizJudgeStoredResult})의 ID와 다르다. 상태 전이는 {@link QuizJudgeJobStatus}를 따른다.
 *
 * <p>DB의 {@code active_creator_id} 생성 컬럼은 매핑하지 않는다. PENDING·RUNNING일 때만 값이 생겨 Creator당 진행 중
 * Job을 하나로 제한한다. 실행 기록은 Job이 끝날 때 {@link #recordExecution}으로 남긴다.
 */
@Entity
@Table(name = "quiz_judge_job")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class QuizJudgeJob {

    /** 추정 비용 컬럼(DECIMAL(20, 10))의 소수 자릿수. */
    private static final int COST_SCALE = 10;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "job_id")
    private Long jobId;

    @Column(name = "series_id", nullable = false, updatable = false)
    private Long seriesId;

    @Column(name = "quiz_id", nullable = false, updatable = false)
    private Long quizId;

    @Column(name = "revision_id", nullable = false, updatable = false)
    private Long revisionId;

    @Column(name = "creator_id", nullable = false, updatable = false)
    private Long creatorId;

    @Column(name = "requested_by_member_id", nullable = false, updatable = false)
    private Long requestedByMemberId;

    @Column(name = "request_id", nullable = false, updatable = false, length = 36)
    private String requestId;

    @Column(name = "attempt_no", nullable = false, updatable = false)
    private int attemptNo;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private QuizJudgeJobStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "failure_code", length = 40)
    private QuizJudgeFailure failureCode;

    @Column(name = "requested_at", nullable = false, updatable = false)
    private Instant requestedAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "requested_model", length = 100)
    private String requestedModel;

    @Column(name = "response_model", length = 100)
    private String responseModel;

    @Column(name = "http_request_count")
    private Integer httpRequestCount;

    @Column(name = "usage_complete")
    private Boolean usageComplete;

    @Column(name = "estimated_cost_usd", precision = 20, scale = 10)
    private BigDecimal estimatedCostUsd;

    @Column(name = "pricing_version", length = 40)
    private String pricingVersion;

    @Column(name = "cost_complete")
    private Boolean costComplete;

    public QuizJudgeJob(QuizJudgeSeries series, Long creatorId, Long requestedByMemberId, String requestId,
                        int attemptNo, Instant now) {
        this.seriesId = Objects.requireNonNull(series.getSeriesId(), "seriesId");
        this.quizId = series.getQuizId();
        this.revisionId = series.getRevisionId();
        this.creatorId = Objects.requireNonNull(creatorId, "creatorId");
        this.requestedByMemberId = Objects.requireNonNull(requestedByMemberId, "requestedByMemberId");
        this.requestId = Objects.requireNonNull(requestId, "requestId");
        this.attemptNo = attemptNo;
        this.status = QuizJudgeJobStatus.PENDING;
        this.requestedAt = Objects.requireNonNull(now, "now");
        this.updatedAt = now;
    }

    public boolean isOwnedBy(Long creatorId) {
        return this.creatorId.equals(creatorId);
    }

    /** 같은 requestId 재요청이 같은 대상인지. */
    public boolean targets(Long quizId, Long revisionId) {
        return this.quizId.equals(quizId) && this.revisionId.equals(revisionId);
    }

    /** RUNNING Job을 결과 저장과 함께 끝낸다. 호출자는 행을 잠근 뒤 RUNNING임을 확인하고 부른다. */
    public void succeed(Instant now) {
        requireRunning();
        this.status = QuizJudgeJobStatus.SUCCEEDED;
        this.completedAt = Objects.requireNonNull(now, "now");
        this.updatedAt = now;
    }

    /** RUNNING Job을 FAILED·UNKNOWN으로 끝낸다. 호출자는 행을 잠근 뒤 RUNNING임을 확인하고 부른다. */
    public void finish(QuizJudgeJobStatus status, QuizJudgeFailure failure, Instant now) {
        requireRunning();
        if (status != QuizJudgeJobStatus.FAILED && status != QuizJudgeJobStatus.UNKNOWN) {
            throw new IllegalArgumentException("FAILED나 UNKNOWN으로만 끝낼 수 있습니다.");
        }
        this.status = status;
        this.failureCode = Objects.requireNonNull(failure, "failure");
        this.completedAt = Objects.requireNonNull(now, "now");
        this.updatedAt = now;
    }

    /**
     * 실행 기록의 요약을 남긴다. 단가가 없으면 비용 컬럼은 모두 비우고, 계산할 수 있는 요청이 없으면 금액만 비운다.
     * 일부만 계산한 금액은 {@code costComplete=false}로 남겨 전체 비용처럼 보이지 않게 한다.
     */
    public void recordExecution(QuizJudgeExecution execution) {
        this.requestedModel = limit(execution.requestedModel());
        this.responseModel = limit(execution.responseModel());
        this.httpRequestCount = execution.httpRequestCount();
        this.usageComplete = execution.usageComplete();
        QuizJudgeExecution.CostEstimate cost = execution.cost();
        this.estimatedCostUsd = cost == null || cost.usd() == null ? null
                : cost.usd().setScale(COST_SCALE, RoundingMode.HALF_UP);
        this.pricingVersion = cost == null ? null : cost.pricingVersion();
        this.costComplete = cost == null ? null : cost.complete();
    }

    private void requireRunning() {
        if (status != QuizJudgeJobStatus.RUNNING) {
            throw new IllegalStateException("RUNNING Job만 끝낼 수 있습니다.");
        }
    }

    /** Provider가 준 모델 이름은 컬럼보다 길면 남기지 않는다. 요청 모델은 설정 검증을 거친 값이다. */
    static String limit(String value) {
        return value != null && value.length() <= 100 ? value : null;
    }
}
