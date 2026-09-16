package kr.co.cking.event.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import kr.co.cking.common.exception.BusinessException;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

@Entity
@Table(name = "event")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Event {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "event_id")
    private Long eventId;

    @Column(name = "creator_id", nullable = false)
    private Long creatorId;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "start_at", nullable = false)
    private LocalDateTime startAt;

    @Column(name = "end_at", nullable = false)
    private LocalDateTime endAt;

    @Column(name = "winner_count", nullable = false)
    private int winnerCount;

    @Enumerated(EnumType.STRING)
    @Column(name = "draw_method", nullable = false, length = 30)
    private DrawMethod drawMethod;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private EventStatus status;

    @Column(name = "created_by", nullable = false)
    private Long createdBy;

    @Column(name = "request_id", nullable = false, length = 36)
    private String requestId;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    public Event(
            Long creatorId,
            String title,
            String description,
            LocalDateTime startAt,
            LocalDateTime endAt,
            int winnerCount,
            DrawMethod drawMethod,
            Long createdBy,
            String requestId
    ) {
        this.creatorId = creatorId;
        this.title = title;
        this.description = description;
        this.startAt = startAt;
        this.endAt = endAt;
        this.winnerCount = winnerCount;
        this.drawMethod = drawMethod;
        this.status = EventStatus.DRAFT;
        this.createdBy = createdBy;
        this.requestId = requestId;
        this.createdAt = LocalDateTime.now(ZoneOffset.UTC);
    }

    public void requestApproval() {
        requireStatus(EventStatus.DRAFT);
        status = EventStatus.PENDING_APPROVAL;
    }

    public void approve() {
        requireStatus(EventStatus.PENDING_APPROVAL);
        status = EventStatus.SCHEDULED;
    }

    public void reject() {
        requireStatus(EventStatus.PENDING_APPROVAL);
        status = EventStatus.REJECTED;
    }

    public void changeToDraft() {
        requireStatus(EventStatus.REJECTED);
        status = EventStatus.DRAFT;
    }

    private void requireStatus(EventStatus expected) {
        // 상태 전이 전제조건을 aggregate 내부에서 일관되게 검증한다.
        if (status != expected) {
            throw new BusinessException(EventErrorCode.INVALID_STATE);
        }
    }
}
