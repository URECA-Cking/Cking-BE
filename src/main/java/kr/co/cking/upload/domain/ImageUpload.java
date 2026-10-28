package kr.co.cking.upload.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 앱 밖 파이프라인으로 처리하는 이미지 업로드 한 건(#780). 결과는 조건부 UPDATE로 한 번만 반영한다. */
@Entity
@Table(name = "image_upload")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ImageUpload {

    @Id
    @Column(name = "upload_id", length = 36)
    private String uploadId;

    @Column(name = "member_id", nullable = false, updatable = false)
    private Long memberId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40, updatable = false)
    private ImageUploadPurpose purpose;

    @Column(name = "object_key", nullable = false, unique = true, updatable = false, length = 200)
    private String objectKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ImageUploadStatus status;

    @Column(name = "failure_reason", length = 40)
    private String failureReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    private ImageUpload(Long memberId, ImageUploadPurpose purpose, String objectKey, Instant now) {
        this.uploadId = UUID.randomUUID().toString();
        this.memberId = memberId;
        this.purpose = purpose;
        this.objectKey = objectKey;
        this.status = ImageUploadStatus.PENDING;
        this.createdAt = now;
    }

    public static ImageUpload pending(Long memberId, ImageUploadPurpose purpose, String objectKey, Instant now) {
        return new ImageUpload(memberId, purpose, objectKey, now);
    }
}
