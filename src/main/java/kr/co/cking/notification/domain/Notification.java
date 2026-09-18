package kr.co.cking.notification.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 당첨 결과를 사용자에게 전달하는 인앱 알림을 표현한다. */
@Entity
@Table(name = "notification")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Notification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long notificationId;

    @Column(name = "member_id", nullable = false, updatable = false)
    private Long memberId;

    @Column(name = "event_id", nullable = false, updatable = false)
    private Long eventId;

    @Column(name = "drawing_id", nullable = false, updatable = false)
    private Long drawingId;

    @Column(name = "winner_id", nullable = false, updatable = false)
    private Long winnerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, updatable = false, length = 30)
    private NotificationType type;

    @Column(name = "title", nullable = false, updatable = false, length = 200)
    private String title;

    @Column(name = "body", nullable = false, updatable = false, columnDefinition = "TEXT")
    private String body;

    @Column(name = "read_at")
    private Instant readAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** 결과 공개 시 생성할 Notification의 변경 불가 정보를 설정한다. */
    public Notification(Long memberId, Long eventId, Long drawingId, Long winnerId,
                        NotificationType type, String title, String body) {
        this.memberId = memberId;
        this.eventId = eventId;
        this.drawingId = drawingId;
        this.winnerId = winnerId;
        this.type = type;
        this.title = title;
        this.body = body;
    }

    /** 생성 시각을 지정해 알림을 생성한다. */
    public Notification(Long memberId, Long eventId, Long drawingId, Long winnerId,
                        NotificationType type, String title, String body, Instant createdAt) {
        this(memberId, eventId, drawingId, winnerId, type, title, body);
        this.createdAt = createdAt;
    }

    /** 저장 직전에 생성 시각이 비어 있으면 현재 시각을 설정한다. */
    @PrePersist
    void assignCreatedAtIfMissing() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    /** 요청 사용자가 이 Notification의 소유자인지 확인한다. */
    public boolean isOwnedBy(Long userId) {
        return memberId.equals(userId);
    }

    /** 아직 읽지 않은 경우에만 최초 읽음 시각을 기록한다. */
    public void markAsRead(Instant readAt) {
        if (this.readAt == null) {
            this.readAt = readAt;
        }
    }
}
