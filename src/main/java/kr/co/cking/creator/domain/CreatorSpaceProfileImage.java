package kr.co.cking.creator.domain;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** Creator가 올린 Space 전용 이미지(프로필 사진·배너)의 소유·정리 기록이다. 종류는 {@link CreatorSpaceImageKind}로 구분한다. */
@Entity
@Table(name = "creator_space_profile_image")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CreatorSpaceProfileImage {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "profile_image_id") private Long profileImageId;
    @Column(name = "object_key", nullable = false, unique = true, updatable = false, length = 200) private String objectKey;
    @Column(name = "creator_id", nullable = false, updatable = false) private Long creatorId;
    @Enumerated(EnumType.STRING) @Column(name = "image_kind", nullable = false, updatable = false, length = 20) private CreatorSpaceImageKind imageKind;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private CreatorSpaceProfileImageStatus status;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
    @Column(name = "status_changed_at", nullable = false) private Instant statusChangedAt;
    private CreatorSpaceProfileImage(String key, Long creatorId, CreatorSpaceImageKind imageKind, Instant now) {
        this.objectKey = key; this.creatorId = creatorId; this.imageKind = imageKind;
        this.status = CreatorSpaceProfileImageStatus.UPLOADING;
        this.createdAt = now; this.statusChangedAt = now;
    }
    /** 프로필 사진 업로드 기록을 만든다. */
    public static CreatorSpaceProfileImage uploading(String key, Long creatorId, Instant now) {
        return new CreatorSpaceProfileImage(key, creatorId, CreatorSpaceImageKind.PROFILE, now);
    }
    /** 배너 이미지 업로드 기록을 만든다. */
    public static CreatorSpaceProfileImage uploadingBanner(String key, Long creatorId, Instant now) {
        return new CreatorSpaceProfileImage(key, creatorId, CreatorSpaceImageKind.BANNER, now);
    }
}
