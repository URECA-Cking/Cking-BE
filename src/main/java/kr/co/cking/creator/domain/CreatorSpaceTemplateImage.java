package kr.co.cking.creator.domain;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 관리자가 템플릿용으로 올린 이미지(프로필·배너)의 정리 기록이다. 어떤 템플릿·Space가 이 이미지를 쓰는지는
 * 기록하지 않고, 정리 작업이 URL 컬럼을 대조해 판단한다.
 */
@Entity
@Table(name = "creator_space_template_image")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CreatorSpaceTemplateImage {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "template_image_id") private Long templateImageId;
    @Column(name = "object_key", nullable = false, unique = true, updatable = false, length = 200) private String objectKey;
    @Enumerated(EnumType.STRING) @Column(name = "image_kind", nullable = false, updatable = false, length = 20) private CreatorSpaceImageKind imageKind;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private CreatorSpaceTemplateImageStatus status;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
    @Column(name = "status_changed_at", nullable = false) private Instant statusChangedAt;

    private CreatorSpaceTemplateImage(String key, CreatorSpaceImageKind imageKind, Instant now) {
        this.objectKey = key; this.imageKind = imageKind;
        this.status = CreatorSpaceTemplateImageStatus.UPLOADING;
        this.createdAt = now; this.statusChangedAt = now;
    }

    /** 저장소에 올리기 전에 남기는 업로드 기록을 만든다. 올리다 실패해도 기록이 남아 정리 대상이 된다. */
    public static CreatorSpaceTemplateImage uploading(String key, CreatorSpaceImageKind imageKind, Instant now) {
        return new CreatorSpaceTemplateImage(key, imageKind, now);
    }
}
