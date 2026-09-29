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

/**
 * 저장소에 올린 게시글 이미지 한 장의 업로드 기록.
 *
 * <p>생성 뒤의 상태·연결 변경은 경합을 막기 위해 조건부 UPDATE로만 한다({@code CreatorPostImageRepository}).
 */
@Entity
@Table(name = "creator_post_image")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CreatorPostImage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "image_id")
    private Long imageId;

    @Column(name = "object_key", nullable = false, updatable = false, length = 200)
    private String objectKey;

    @Column(name = "creator_id", nullable = false, updatable = false)
    private Long creatorId;

    @Column(name = "post_id")
    private Long postId;

    @Column(name = "display_order")
    private Integer displayOrder;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private PostImageStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "status_changed_at", nullable = false)
    private Instant statusChangedAt;

    /** 저장소 put 전에 남기는 UPLOADING 기록을 만든다. */
    public static CreatorPostImage uploading(String objectKey, Long creatorId, Instant now) {
        CreatorPostImage image = new CreatorPostImage();
        image.objectKey = Objects.requireNonNull(objectKey, "objectKey");
        image.creatorId = Objects.requireNonNull(creatorId, "creatorId");
        image.status = PostImageStatus.UPLOADING;
        image.createdAt = Objects.requireNonNull(now, "now");
        image.statusChangedAt = now;
        return image;
    }
}
