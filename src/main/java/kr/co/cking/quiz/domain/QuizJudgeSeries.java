package kr.co.cking.quiz.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import kr.co.cking.quiz.judge.QuizJudgeFingerprint;
import kr.co.cking.quiz.judge.QuizJudgeSettings;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Objects;

/**
 * revision 하나의 평가 묶음(이슈 #529). revision마다 하나이며, 첫 평가 요청의 Judge 입력 hash와 실행 설정 snapshot을
 * 고정한다. 설정이 바뀌어도 새 묶음을 만들지 않으므로 기술적 시도 횟수({@link #MAX_ATTEMPTS})는 초기화되지 않는다.
 * 시도 번호는 이 행을 잠근 채 {@link #allocateAttempt}로 할당한다.
 */
@Entity
@Table(name = "quiz_judge_series")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class QuizJudgeSeries {

    /** 최초 평가를 포함한 revision당 기술적 시도 횟수 상한. */
    public static final int MAX_ATTEMPTS = 2;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "series_id")
    private Long seriesId;

    @Column(name = "quiz_id", nullable = false, updatable = false)
    private Long quizId;

    @Column(name = "revision_id", nullable = false, updatable = false)
    private Long revisionId;

    @Column(name = "content_text_sha256", nullable = false, updatable = false, length = 64)
    private String contentTextSha256;

    @Column(name = "revision_sha256", nullable = false, updatable = false, length = 64)
    private String revisionSha256;

    @Column(name = "input_sha256", nullable = false, updatable = false, length = 64)
    private String inputSha256;

    @Column(name = "settings_fingerprint", nullable = false, updatable = false, length = 64)
    private String settingsFingerprint;

    @Column(name = "model", nullable = false, updatable = false, length = 100)
    private String model;

    @Column(name = "reasoning_effort", nullable = false, updatable = false, length = 20)
    private String reasoningEffort;

    @Column(name = "max_output_tokens", nullable = false, updatable = false)
    private int maxOutputTokens;

    @Column(name = "reason_max_length", nullable = false, updatable = false)
    private int reasonMaxLength;

    @Column(name = "prompt_version", nullable = false, updatable = false, length = 40)
    private String promptVersion;

    @Column(name = "rubric_version", nullable = false, updatable = false, length = 40)
    private String rubricVersion;

    @Column(name = "schema_version", nullable = false, updatable = false, length = 40)
    private String schemaVersion;

    @Column(name = "policy_version", nullable = false, updatable = false, length = 40)
    private String policyVersion;

    @Column(name = "instructions_sha256", nullable = false, updatable = false, length = 64)
    private String instructionsSha256;

    @Column(name = "schema_sha256", nullable = false, updatable = false, length = 64)
    private String schemaSha256;

    @Column(name = "allocated_attempts", nullable = false)
    private int allocatedAttempts;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public QuizJudgeSeries(Long quizId, Long revisionId, String contentTextSha256, String revisionSha256,
                           String inputSha256, QuizJudgeSettings settings, Instant now) {
        this.quizId = Objects.requireNonNull(quizId, "quizId");
        this.revisionId = Objects.requireNonNull(revisionId, "revisionId");
        this.contentTextSha256 = Objects.requireNonNull(contentTextSha256, "contentTextSha256");
        this.revisionSha256 = Objects.requireNonNull(revisionSha256, "revisionSha256");
        this.inputSha256 = Objects.requireNonNull(inputSha256, "inputSha256");
        this.settingsFingerprint = QuizJudgeFingerprint.settingsFingerprint(settings);
        this.model = settings.model();
        this.reasoningEffort = settings.reasoningEffort();
        this.maxOutputTokens = settings.maxOutputTokens();
        this.reasonMaxLength = settings.reasonMaxLength();
        this.promptVersion = settings.promptVersion();
        this.rubricVersion = settings.rubricVersion();
        this.schemaVersion = settings.schemaVersion();
        this.policyVersion = settings.policyVersion();
        this.instructionsSha256 = settings.instructionsSha256();
        this.schemaSha256 = settings.schemaSha256();
        this.allocatedAttempts = 0;
        this.createdAt = Objects.requireNonNull(now, "now");
        this.updatedAt = now;
    }

    public boolean hasAttemptLeft() {
        return allocatedAttempts < MAX_ATTEMPTS;
    }

    /** 다음 시도 번호(1부터)를 할당한다. 호출자는 이 행을 잠근 뒤 부르며, Job 저장과 같은 Transaction이어야 한다. */
    public int allocateAttempt(Instant now) {
        if (!hasAttemptLeft()) {
            throw new IllegalStateException("평가 시도 한도를 넘을 수 없습니다.");
        }
        allocatedAttempts++;
        updatedAt = Objects.requireNonNull(now, "now");
        return allocatedAttempts;
    }

    /** 첫 요청 때 고정한 실행 설정. */
    public QuizJudgeSettings settings() {
        return new QuizJudgeSettings(model, reasoningEffort, maxOutputTokens, reasonMaxLength, promptVersion,
                rubricVersion, schemaVersion, policyVersion, instructionsSha256, schemaSha256);
    }
}
