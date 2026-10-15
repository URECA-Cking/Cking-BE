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

/**
 * 사용자에게 전달하는 인앱 알림을 표현한다. 당첨 알림은 {@code eventId}·{@code drawingId}·{@code winnerId}를 모두 갖고,
 * 제재 알림(이슈 #572)은 이 셋이 모두 비고 {@code sanctionId}를 갖는다.
 * 미당첨자 알림(이슈 #605)은 {@code eventId}·{@code drawingId}만 갖고 {@code winnerId}는 비운다. 유형별 조합은 DB CHECK가 강제한다.
 */
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

    @Column(name = "event_id", updatable = false)
    private Long eventId;

    @Column(name = "drawing_id", updatable = false)
    private Long drawingId;

    @Column(name = "winner_id", updatable = false)
    private Long winnerId;

    @Column(name = "sanction_id", updatable = false)
    private Long sanctionId;

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

    /** 제재(댓글 작성 제한) 시작·해제를 알리는 Notification을 만든다. 이벤트·추첨·당첨 값은 비운다. */
    public static Notification forSanction(
            Long memberId, Long sanctionId, NotificationType type, String title, String body) {
        Notification notification = new Notification();
        notification.memberId = memberId;
        notification.sanctionId = sanctionId;
        notification.type = type;
        notification.title = title;
        notification.body = body;
        return notification;
    }

    /** 최초 추첨에서 당첨되지 못한 Member에게 결과 발표를 알리는 Notification을 만든다. Winner가 없어 {@code winnerId}는 비운다. */
    public static Notification forNonWinner(
            Long memberId, Long eventId, Long drawingId, String title, String body) {
        return new Notification(memberId, eventId, drawingId, null, NotificationType.INITIAL_NON_WINNER, title, body);
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
