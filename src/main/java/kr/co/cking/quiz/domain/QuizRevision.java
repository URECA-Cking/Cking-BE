package kr.co.cking.quiz.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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

/** Quiz의 한 버전. INSERT 후 바꾸지 않으며, 수정은 새 revision으로 만든다. */
@Entity
@Immutable
@Table(name = "quiz_revision")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class QuizRevision {

    public static final int MAX_TITLE_LENGTH = 100;
    public static final int MAX_PROMPT_VERSION_LENGTH = 50;

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

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public QuizRevision(Long quizId, int revisionNo, String title, String promptVersion, int questionCount,
                        int optionCount, String revisionSha256, Instant now) {
        this.quizId = Objects.requireNonNull(quizId, "quizId");
        this.revisionNo = revisionNo;
        this.title = Objects.requireNonNull(title, "title");
        this.promptVersion = Objects.requireNonNull(promptVersion, "promptVersion");
        this.questionCount = questionCount;
        this.optionCount = optionCount;
        this.revisionSha256 = Objects.requireNonNull(revisionSha256, "revisionSha256");
        this.createdAt = Objects.requireNonNull(now, "now");
    }
}
