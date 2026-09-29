package kr.co.cking.post.domain;

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

/** Creator Space 게시글. 이미지는 {@link CreatorPostImage}가 게시글 ID로 연결한다. */
@Entity
@Table(name = "creator_post")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CreatorPost {

    public static final int MAX_CONTENT_LENGTH = 2000;
    public static final int MAX_IMAGE_COUNT = 5;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "post_id")
    private Long postId;

    @Column(name = "creator_id", nullable = false, updatable = false)
    private Long creatorId;

    @Column(name = "content", nullable = false, length = MAX_CONTENT_LENGTH)
    private String content;

    @Enumerated(EnumType.STRING)
    @Column(name = "visibility", nullable = false, length = 20)
    private PostVisibility visibility;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public CreatorPost(Long creatorId, String content, PostVisibility visibility, Instant now) {
        this.creatorId = Objects.requireNonNull(creatorId, "creatorId");
        this.content = Objects.requireNonNull(content, "content");
        this.visibility = Objects.requireNonNull(visibility, "visibility");
        this.createdAt = Objects.requireNonNull(now, "now");
        this.updatedAt = now;
    }

    public void update(String content, PostVisibility visibility, Instant now) {
        this.content = Objects.requireNonNull(content, "content");
        this.visibility = Objects.requireNonNull(visibility, "visibility");
        this.updatedAt = Objects.requireNonNull(now, "now");
    }

    public boolean isWrittenBy(Long creatorId) {
        return this.creatorId.equals(creatorId);
    }
}
