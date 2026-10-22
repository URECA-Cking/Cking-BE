package kr.co.cking.event.domain;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** Creator가 Event 배너로 올린 이미지의 소유·정리 기록이다(이슈 #748). Event와는 object key로만 이어진다. */
@Entity
@Table(name = "event_banner_image")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class EventBannerImage {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "banner_image_id") private Long bannerImageId;
    @Column(name = "object_key", nullable = false, unique = true, updatable = false, length = 200) private String objectKey;
    @Column(name = "creator_id", nullable = false, updatable = false) private Long creatorId;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private EventBannerImageStatus status;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
    @Column(name = "status_changed_at", nullable = false) private Instant statusChangedAt;

    private EventBannerImage(String key, Long creatorId, Instant now) {
        this.objectKey = key; this.creatorId = creatorId;
        this.status = EventBannerImageStatus.UPLOADING;
        this.createdAt = now; this.statusChangedAt = now;
    }

    /** 저장소에 올리기 전 업로드 기록을 만든다. 저장이 끝나면 UPLOADED로 바뀐다. */
    public static EventBannerImage uploading(String key, Long creatorId, Instant now) {
        return new EventBannerImage(key, creatorId, now);
    }
}
