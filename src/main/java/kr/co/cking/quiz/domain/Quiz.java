package kr.co.cking.quiz.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import kr.co.cking.common.exception.BusinessException;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/**
 * Creator가 소유한 AI Quiz. 원본 videoId·contentText는 바꾸지 않고, 문항은 {@link QuizRevision}에 둔다.
 *
 * <p>공개 상태와 게시 revision 포인터는 함께 바뀐다. 게시 중에는 게시 revision이 필수이며, 마감 시 마지막 게시 포인터를 보존한다.
 *
 * <p>첫 게시 때 내용을 잠그고(content_locked) 게시한 revision ID·해시를 고정한다. 게시 취소해도 지우지 않으며, 이후에는
 * 고정한 revision만 다시 게시할 수 있다. 과거 게시 revision을 확인할 수 없는 기존 Quiz는 잠금만 있고 고정 revision이 없어
 * 다시 게시할 수 없다.
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

    @Column(name = "closed_at")
    private Instant closedAt;

    @Column(name = "content_locked", nullable = false)
    private boolean contentLocked;

    @Column(name = "locked_revision_id")
    private Long lockedRevisionId;

    @Column(name = "locked_revision_sha256", length = 64)
    private String lockedRevisionSha256;

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

    public boolean isClosed() {
        return publicationStatus == QuizPublicationStatus.CLOSED;
    }

    public void requireNotClosed() {
        if (isClosed()) {
            throw new BusinessException(QuizErrorCode.QUIZ_CLOSED);
        }
    }

    /** 게시 이력과 최초 게시 고정을 보존한 채 운영을 종료한다. */
    public void close(Instant now) {
        if (isClosed()) {
            return;
        }
        if (publicationStatus == QuizPublicationStatus.NOT_PUBLISHED) {
            throw new BusinessException(QuizErrorCode.QUIZ_CLOSE_NOT_ALLOWED);
        }
        Instant closingTime = Objects.requireNonNull(now, "now").truncatedTo(ChronoUnit.MICROS);
        publicationStatus = QuizPublicationStatus.CLOSED;
        closedAt = closingTime;
        updatedAt = closedAt;
    }

    public boolean isPublishing(Long revisionId) {
        return isPublished() && publishedRevisionId.equals(revisionId);
    }

    /** 저장된 원본 텍스트가 생성 시 기록한 해시와 같은지 확인한다. */
    public boolean hasIntactContentText() {
        return QuizRevisionFingerprint.contentTextSha256(contentText).equals(contentTextSha256);
    }

    /** 잠기지 않았거나 이 revision·해시로 고정돼 있어 게시할 수 있는지. */
    public boolean canPublish(Long revisionId, String revisionSha256) {
        return !isClosed() && (!contentLocked || (lockedRevisionId != null && lockedRevisionId.equals(revisionId)
                && lockedRevisionSha256.equals(revisionSha256)));
    }

    /**
     * 게시 조건 검증은 호출자가 마친 뒤 부른다. 첫 게시면 내용을 잠그고 이 revision·해시를 고정한다. 잠긴 뒤에는 고정한
     * revision만 게시할 수 있다.
     *
     * <p>{@code publishedAt}은 현재 게시가 시작된 시각이고 {@code unpublishedAt}은 그 게시가 취소된 시각이므로,
     * 게시하면 이전 게시 취소 시각을 지운다.
     */
    public void publish(Long revisionId, String revisionSha256, Instant now) {
        requireNotClosed();
        Objects.requireNonNull(revisionSha256, "revisionSha256");
        if (!canPublish(revisionId, revisionSha256)) {
            throw new IllegalStateException("고정한 revision만 게시할 수 있습니다.");
        }
        if (!contentLocked) {
            this.contentLocked = true;
            this.lockedRevisionId = revisionId;
            this.lockedRevisionSha256 = revisionSha256;
        }
        this.publicationStatus = QuizPublicationStatus.PUBLISHED;
        this.publishedRevisionId = Objects.requireNonNull(revisionId, "revisionId");
        this.publishedAt = Objects.requireNonNull(now, "now");
        this.unpublishedAt = null;
        this.updatedAt = now;
    }

    public void unpublish(Instant now) {
        requireNotClosed();
        if (!isPublished()) {
            throw new IllegalStateException("게시 중인 Quiz만 게시 취소할 수 있습니다.");
        }
        this.publicationStatus = QuizPublicationStatus.UNPUBLISHED;
        this.publishedRevisionId = null;
        this.unpublishedAt = Objects.requireNonNull(now, "now");
        this.updatedAt = now;
    }
}
