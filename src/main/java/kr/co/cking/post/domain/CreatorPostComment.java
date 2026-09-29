package kr.co.cking.post.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Objects;

/** Creator Space 게시글 댓글. */
@Entity
@Table(name = "creator_post_comment")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CreatorPostComment {

    public static final int MAX_CONTENT_LENGTH = 500;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "comment_id")
    private Long commentId;

    @Column(name = "post_id", nullable = false, updatable = false)
    private Long postId;

    @Column(name = "member_id", nullable = false, updatable = false)
    private Long memberId;

    @Column(name = "content", nullable = false, length = MAX_CONTENT_LENGTH)
    private String content;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public CreatorPostComment(Long postId, Long memberId, String content, Instant now) {
        this.postId = Objects.requireNonNull(postId, "postId");
        this.memberId = Objects.requireNonNull(memberId, "memberId");
        this.content = Objects.requireNonNull(content, "content");
        this.createdAt = Objects.requireNonNull(now, "now");
        this.updatedAt = now;
    }

    public void update(String content, Instant now) {
        this.content = Objects.requireNonNull(content, "content");
        this.updatedAt = Objects.requireNonNull(now, "now");
    }

    public boolean isWrittenBy(Long memberId) {
        return this.memberId.equals(memberId);
    }

    public boolean belongsTo(Long postId) {
        return this.postId.equals(postId);
    }
}
