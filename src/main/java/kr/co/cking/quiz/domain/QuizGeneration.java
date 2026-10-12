package kr.co.cking.quiz.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Creator의 YouTube 영상 Quiz 생성 요청과 진행 상태(이슈 #525). 상태 전이는 {@link QuizGenerationStatus}를 따른다.
 *
 * <p>DB의 {@code active_creator_id} 생성 컬럼은 매핑하지 않는다. PENDING·RUNNING일 때만 값이 생겨 Creator당 진행 중
 * 작업을 하나로 제한한다.
 */
@Entity
@Table(name = "quiz_generation")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class QuizGeneration {

    public static final int OPTION_COUNT = 4;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "generation_id")
    private Long generationId;

    @Column(name = "creator_id", nullable = false, updatable = false)
    private Long creatorId;

    @Column(name = "requested_by_member_id", nullable = false, updatable = false)
    private Long requestedByMemberId;

    @Column(name = "request_id", nullable = false, updatable = false, length = 36)
    private String requestId;

    @Column(name = "request_fingerprint", nullable = false, updatable = false, length = 64)
    private String requestFingerprint;

    @Column(name = "video_id", nullable = false, updatable = false, length = 20)
    private String videoId;

    @Column(name = "title", nullable = false, updatable = false, length = QuizRevision.MAX_TITLE_LENGTH)
    private String title;

    @Column(name = "question_count", nullable = false, updatable = false)
    private int questionCount;

    @Column(name = "option_count", nullable = false, updatable = false)
    private int optionCount;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private QuizGenerationStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "failure_code", length = 40)
    private QuizGenerationFailure failureCode;

    @Column(name = "quiz_id")
    private Long quizId;

    @Column(name = "revision_id")
    private Long revisionId;

    @Column(name = "requested_at", nullable = false, updatable = false)
    private Instant requestedAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public QuizGeneration(Long creatorId, Long requestedByMemberId, String requestId, String videoId, String title,
                          int questionCount, Instant now) {
        this.creatorId = Objects.requireNonNull(creatorId, "creatorId");
        this.requestedByMemberId = Objects.requireNonNull(requestedByMemberId, "requestedByMemberId");
        this.requestId = Objects.requireNonNull(requestId, "requestId");
        this.videoId = Objects.requireNonNull(videoId, "videoId");
        this.title = Objects.requireNonNull(title, "title");
        this.questionCount = questionCount;
        this.optionCount = OPTION_COUNT;
        this.requestFingerprint = fingerprint(videoId, title, questionCount, OPTION_COUNT);
        this.status = QuizGenerationStatus.PENDING;
        this.requestedAt = Objects.requireNonNull(now, "now");
        this.updatedAt = now;
    }

    public boolean isOwnedBy(Long creatorId) {
        return this.creatorId.equals(creatorId);
    }

    /** RUNNING 작업의 결과를 기록한다. 호출자는 행을 잠근 뒤 RUNNING임을 확인하고 부른다. */
    public void succeed(Long quizId, Long revisionId, Instant now) {
        if (status != QuizGenerationStatus.RUNNING) {
            throw new IllegalStateException("RUNNING 작업만 완료할 수 있습니다.");
        }
        this.status = QuizGenerationStatus.SUCCEEDED;
        this.quizId = Objects.requireNonNull(quizId, "quizId");
        this.revisionId = Objects.requireNonNull(revisionId, "revisionId");
        this.completedAt = Objects.requireNonNull(now, "now");
        this.updatedAt = now;
    }

    /**
     * 같은 requestId 재요청을 비교하는 요청 지문. URL 표현이 달라도 videoId가 같으면 같은 입력이다.
     * 각 값을 {@code <UTF-8 바이트 길이>:<값>}으로 이어 붙인 UTF-8 바이트의 SHA-256(소문자 hex)이다.
     */
    public static String fingerprint(String videoId, String title, int questionCount, int optionCount) {
        StringBuilder input = new StringBuilder();
        for (String value : new String[] {videoId, title, String.valueOf(questionCount), String.valueOf(optionCount)}) {
            input.append(value.getBytes(StandardCharsets.UTF_8).length).append(':').append(value);
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(input.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256을 사용할 수 없습니다.", exception);
        }
    }
}
