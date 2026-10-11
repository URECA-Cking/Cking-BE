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

import java.time.Instant;
import java.util.Objects;

/**
 * Creator가 소유한 AI Quiz. 원본 videoId·contentText는 바꾸지 않고, 문항은 {@link QuizRevision}에 둔다.
 *
 * <p>공개 상태와 게시 revision 포인터는 함께 바뀐다. {@code PUBLISHED}일 때만 {@code publishedRevisionId}가 있다.
 */
@Entity
@Table(name = "quiz")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Quiz {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "quiz_id")
    private Long quizId;

    @Column(name = "creator_id", nullable = false, updatable = false)
    private Long creatorId;

    @Column(name = "source_video_id", nullable = false, updatable = false, length = 20)
    private String sourceVideoId;

    @Column(name = "content_text", nullable = false, updatable = false, columnDefinition = "MEDIUMTEXT")
    private String contentText;

    @Column(name = "content_text_sha256", nullable = false, updatable = false, length = 64)
    private String contentTextSha256;

    @Enumerated(EnumType.STRING)
    @Column(name = "publication_status", nullable = false, length = 20)
    private QuizPublicationStatus publicationStatus;

    @Column(name = "published_revision_id")
    private Long publishedRevisionId;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "unpublished_at")
    private Instant unpublishedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public Quiz(Long creatorId, String sourceVideoId, String contentText, Instant now) {
        this.creatorId = Objects.requireNonNull(creatorId, "creatorId");
        this.sourceVideoId = Objects.requireNonNull(sourceVideoId, "sourceVideoId");
        this.contentText = Objects.requireNonNull(contentText, "contentText");
        this.contentTextSha256 = QuizRevisionFingerprint.contentTextSha256(contentText);
        this.publicationStatus = QuizPublicationStatus.NOT_PUBLISHED;
        this.createdAt = Objects.requireNonNull(now, "now");
        this.updatedAt = now;
    }

    public boolean isOwnedBy(Long creatorId) {
        return this.creatorId.equals(creatorId);
    }

    public boolean isPublished() {
        return publicationStatus == QuizPublicationStatus.PUBLISHED;
    }

    public boolean isPublishing(Long revisionId) {
        return isPublished() && publishedRevisionId.equals(revisionId);
    }

    /** 저장된 원본 텍스트가 생성 시 기록한 해시와 같은지 확인한다. */
    public boolean hasIntactContentText() {
        return QuizRevisionFingerprint.contentTextSha256(contentText).equals(contentTextSha256);
    }

    /** 게시 조건 검증은 호출자가 마친 뒤 부른다. 이미 게시 중이면 게시 revision만 바꾼다. */
    public void publish(Long revisionId, Instant now) {
        this.publicationStatus = QuizPublicationStatus.PUBLISHED;
        this.publishedRevisionId = Objects.requireNonNull(revisionId, "revisionId");
        this.publishedAt = Objects.requireNonNull(now, "now");
        this.updatedAt = now;
    }

    public void unpublish(Instant now) {
        if (!isPublished()) {
            throw new IllegalStateException("게시 중인 Quiz만 게시 취소할 수 있습니다.");
        }
        this.publicationStatus = QuizPublicationStatus.UNPUBLISHED;
        this.publishedRevisionId = null;
        this.unpublishedAt = Objects.requireNonNull(now, "now");
        this.updatedAt = now;
    }
}
