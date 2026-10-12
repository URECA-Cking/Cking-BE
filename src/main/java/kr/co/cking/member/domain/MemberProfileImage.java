package kr.co.cking.member.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** Member가 올린 개인 프로필 이미지의 소유·정리 기록이다. */
@Entity
@Table(name = "member_profile_image")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MemberProfileImage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "profile_image_id")
    private Long profileImageId;

    @Column(name = "object_key", nullable = false, updatable = false, length = 200, unique = true)
    private String objectKey;

    @Column(name = "member_id", nullable = false, updatable = false)
    private Long memberId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private MemberProfileImageStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "status_changed_at", nullable = false)
    private Instant statusChangedAt;

    private MemberProfileImage(String objectKey, Long memberId, Instant now) {
        this.objectKey = Objects.requireNonNull(objectKey, "objectKey");
        this.memberId = Objects.requireNonNull(memberId, "memberId");
        this.status = MemberProfileImageStatus.UPLOADING;
        this.createdAt = Objects.requireNonNull(now, "now");
        this.statusChangedAt = now;
    }

    public static MemberProfileImage uploading(String objectKey, Long memberId, Instant now) {
        return new MemberProfileImage(objectKey, memberId, now);
    }
}
