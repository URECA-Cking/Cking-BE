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
import org.hibernate.annotations.Immutable;

import java.time.Instant;
import java.util.Objects;

/**
 * Quiz의 한 버전. INSERT 후 바꾸지 않으며, 수정은 새 revision으로 만든다.
 *
 * <p>AI 생성 revision은 {@link QuizRevisionFingerprint#VERSION} 해시를 쓴다. Creator가 부모 revision에서 문항을
 * 선택·수정·추가한 revision({@link QuizRevisionOrigin#CREATOR_COMPOSED})은 부모 ID·해시와 편집 Member·시각을 함께
 * 기록하고 {@link QuizRevisionFingerprint#VERSION_V2} 해시를 쓴다.
 */
@Entity
@Immutable
@Table(name = "quiz_revision")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class QuizRevision {

    public static final int MAX_TITLE_LENGTH = 100;
    public static final int MAX_PROMPT_VERSION_LENGTH = 50;
    public static final int MIN_COMPOSED_QUESTION_COUNT = 1;
    public static final int MAX_COMPOSED_QUESTION_COUNT = 5;
    public static final int COMPOSED_OPTION_COUNT = 4;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "revision_id")
    private Long revisionId;

    @Column(name = "quiz_id", nullable = false, updatable = false)
    private Long quizId;

    @Column(name = "revision_no", nullable = false, updatable = false)
    private int revisionNo;

    @Column(name = "title", nullable = false, updatable = false, length = MAX_TITLE_LENGTH)
    private String title;

    @Column(name = "prompt_version", nullable = false, updatable = false, length = MAX_PROMPT_VERSION_LENGTH)
    private String promptVersion;

    @Column(name = "question_count", nullable = false, updatable = false)
    private int questionCount;

    @Column(name = "option_count", nullable = false, updatable = false)
    private int optionCount;

    @Column(name = "revision_sha256", nullable = false, updatable = false, length = 64)
    private String revisionSha256;

    @Enumerated(EnumType.STRING)
    @Column(name = "origin", nullable = false, updatable = false, length = 20)
    private QuizRevisionOrigin origin;

    @Column(name = "hash_version", nullable = false, updatable = false, length = 20)
    private String hashVersion;

    @Column(name = "parent_revision_id", updatable = false)
    private Long parentRevisionId;

    @Column(name = "parent_revision_sha256", updatable = false, length = 64)
    private String parentRevisionSha256;

    @Column(name = "composed_by_member_id", updatable = false)
    private Long composedByMemberId;

    @Column(name = "composed_at", updatable = false)
    private Instant composedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** AI 생성 revision. */
    public QuizRevision(Long quizId, int revisionNo, String title, String promptVersion, int questionCount,
                        int optionCount, String revisionSha256, Instant now) {
        this.quizId = Objects.requireNonNull(quizId, "quizId");
        this.revisionNo = revisionNo;
        this.title = Objects.requireNonNull(title, "title");
        this.promptVersion = Objects.requireNonNull(promptVersion, "promptVersion");
        this.questionCount = questionCount;
        this.optionCount = optionCount;
        this.revisionSha256 = Objects.requireNonNull(revisionSha256, "revisionSha256");
        this.origin = QuizRevisionOrigin.AI_GENERATED;
        this.hashVersion = QuizRevisionFingerprint.VERSION;
        this.createdAt = Objects.requireNonNull(now, "now");
    }

    /** Creator가 {@code parent}에서 문항을 선택·수정·추가해 구성한 revision. 해시는 v2로 계산한 값이다. */
    public static QuizRevision composed(QuizRevision parent, int revisionNo, String title, int questionCount,
                                        int optionCount, String revisionSha256, Long composedByMemberId, Instant now) {
        if (questionCount < MIN_COMPOSED_QUESTION_COUNT || questionCount > MAX_COMPOSED_QUESTION_COUNT
                || optionCount != COMPOSED_OPTION_COUNT) {
            throw new IllegalArgumentException("Creator가 구성한 revision은 1~5문항, 4지선다여야 합니다.");
        }
        QuizRevision revision = new QuizRevision(parent.getQuizId(), revisionNo, title, parent.getPromptVersion(),
                questionCount, optionCount, revisionSha256, now);
        revision.origin = QuizRevisionOrigin.CREATOR_COMPOSED;
        revision.hashVersion = QuizRevisionFingerprint.VERSION_V2;
        revision.parentRevisionId = Objects.requireNonNull(parent.getRevisionId(), "parentRevisionId");
        revision.parentRevisionSha256 = parent.getRevisionSha256();
        revision.composedByMemberId = Objects.requireNonNull(composedByMemberId, "composedByMemberId");
        revision.composedAt = now;
        return revision;
    }

    public boolean isCreatorComposed() {
        return origin == QuizRevisionOrigin.CREATOR_COMPOSED;
    }
}
