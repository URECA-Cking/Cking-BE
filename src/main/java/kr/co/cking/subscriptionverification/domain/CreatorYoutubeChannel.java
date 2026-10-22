package kr.co.cking.subscriptionverification.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Locale;
import java.util.Objects;

/** Creator가 구독 인증 대상으로 설정한 단일 YouTube 채널이다. */
@Entity
@Table(name = "creator_youtube_channel")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CreatorYoutubeChannel {

    private static final int MAX_NAME_LENGTH = 100;
    private static final int MAX_HANDLE_LENGTH = 100;

    @Id
    @Column(name = "creator_id")
    private Long creatorId;

    @Column(name = "channel_name", nullable = false, length = MAX_NAME_LENGTH)
    private String channelName;

    @Column(name = "channel_handle", nullable = false, length = MAX_HANDLE_LENGTH, unique = true)
    private String channelHandle;

    @Column(name = "channel_url", nullable = false, length = 500)
    private String channelUrl;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public CreatorYoutubeChannel(Long creatorId, String channelName, String channelHandle, Instant now) {
        if (creatorId == null || creatorId <= 0 || now == null) {
            throw new IllegalArgumentException("Creator YouTube 채널 생성 값이 유효하지 않습니다.");
        }
        this.creatorId = creatorId;
        this.createdAt = now;
        apply(channelName, channelHandle, now);
    }

    public void update(String channelName, String channelHandle, Instant now) {
        if (now == null) {
            throw new IllegalArgumentException("updatedAt은 필수입니다.");
        }
        apply(channelName, channelHandle, now);
    }

    public boolean hasSameConfiguration(String channelName, String channelHandle) {
        return this.channelName.equals(normalizeName(channelName))
                && this.channelHandle.equals(normalizeHandle(channelHandle));
    }

    private void apply(String channelName, String channelHandle, Instant now) {
        String normalizedName = normalizeName(channelName);
        String normalizedHandle = normalizeHandle(channelHandle);
        this.channelName = normalizedName;
        this.channelHandle = normalizedHandle;
        this.channelUrl = "https://www.youtube.com/" + normalizedHandle;
        this.updatedAt = now;
    }

    public static String normalizeName(String channelName) {
        if (channelName == null) {
            throw new IllegalArgumentException("channelName은 필수입니다.");
        }
        String normalized = channelName.trim();
        if (normalized.isBlank() || normalized.length() > MAX_NAME_LENGTH) {
            throw new IllegalArgumentException("channelName이 유효하지 않습니다.");
        }
        return normalized;
    }

    public static String normalizeHandle(String channelHandle) {
        if (channelHandle == null) {
            throw new IllegalArgumentException("channelHandle은 필수입니다.");
        }
        String body = channelHandle.trim();
        while (body.startsWith("@")) {
            body = body.substring(1);
        }
        body = body.toLowerCase(Locale.ROOT);
        if (body.isBlank() || !body.matches("[\\p{L}\\p{N}._-]+")) {
            throw new IllegalArgumentException("channelHandle이 유효하지 않습니다.");
        }
        String normalized = "@" + body;
        if (normalized.length() > MAX_HANDLE_LENGTH) {
            throw new IllegalArgumentException("channelHandle이 너무 깁니다.");
        }
        return normalized;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof CreatorYoutubeChannel channel)) {
            return false;
        }
        return Objects.equals(creatorId, channel.creatorId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(creatorId);
    }
}
