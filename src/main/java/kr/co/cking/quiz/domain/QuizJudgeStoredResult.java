package kr.co.cking.quiz.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import kr.co.cking.quiz.judge.JudgeAggregation;
import kr.co.cking.quiz.judge.QuizJudgeResult;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Objects;

/**
 * SUCCEEDED 평가 Job의 불변 결과(이슈 #529). Job·평가 묶음마다 하나이며, 결과가 생기면 그 revision은 다시 평가하지
 * 않는다. 승인 기록의 {@code judge_evaluation_id}·{@code judge_evaluation_sha256}은 Job이 아니라 이 결과의 ID와
 * {@link #resultSha256}이다. 문항 × rubric 판정은 {@link QuizJudgeStoredResultItem}에 둔다.
 */
@Entity
@Table(name = "quiz_judge_result")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class QuizJudgeStoredResult {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "result_id")
    private Long resultId;

    @Column(name = "job_id", nullable = false, updatable = false)
    private Long jobId;

    @Column(name = "series_id", nullable = false, updatable = false)
    private Long seriesId;

    @Column(name = "revision_id", nullable = false, updatable = false)
    private Long revisionId;

    @Column(name = "input_sha256", nullable = false, updatable = false, length = 64)
    private String inputSha256;

    @Column(name = "settings_fingerprint", nullable = false, updatable = false, length = 64)
    private String settingsFingerprint;

    @Column(name = "policy_version", nullable = false, updatable = false, length = 40)
    private String policyVersion;

    @Enumerated(EnumType.STRING)
    @Column(name = "aggregation", nullable = false, updatable = false, length = 20)
    private JudgeAggregation aggregation;

    @Column(name = "question_count", nullable = false, updatable = false)
    private int questionCount;

    @Column(name = "result_sha256", nullable = false, updatable = false, length = 64)
    private String resultSha256;

    @Column(name = "completed_at", nullable = false, updatable = false)
    private Instant completedAt;

    public QuizJudgeStoredResult(QuizJudgeJob job, QuizJudgeResult result, Instant now) {
        this.jobId = Objects.requireNonNull(job.getJobId(), "jobId");
        this.seriesId = job.getSeriesId();
        this.revisionId = job.getRevisionId();
        this.inputSha256 = result.inputSha256();
        this.settingsFingerprint = result.settingsFingerprint();
        this.policyVersion = result.settings().policyVersion();
        this.aggregation = result.evaluation().aggregation();
        this.questionCount = result.evaluation().questions().size();
        this.resultSha256 = result.resultSha256();
        this.completedAt = Objects.requireNonNull(now, "now");
    }
}
