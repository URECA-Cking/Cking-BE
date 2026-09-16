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

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    /** Creator가 작성한 새 Event를 초안 상태로 생성한다. */
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

    /** 초안 Event를 승인 대기 상태로 전이한다. */
    public void requestApproval() {
        requireStatus(EventStatus.DRAFT);
        status = EventStatus.PENDING_APPROVAL;
    }

    /** 승인 대기 Event를 예약 상태로 전이한다. */
    public void approve() {
        requireStatus(EventStatus.PENDING_APPROVAL);
        status = EventStatus.SCHEDULED;
    }

    /** 승인 대기 Event를 거절 상태로 전이한다. */
    public void reject() {
        requireStatus(EventStatus.PENDING_APPROVAL);
        status = EventStatus.REJECTED;
    }

    /** 거절된 Event를 다시 초안 상태로 전이한다. */
    public void changeToDraft() {
        requireStatus(EventStatus.REJECTED);
        status = EventStatus.DRAFT;
    }

    /** 초안 Event의 운영 정보를 변경한다. */
    public void update(
            String title,
            String description,
            LocalDateTime startAt,
            LocalDateTime endAt,
            int winnerCount,
            DrawMethod drawMethod
    ) {
        requireStatus(EventStatus.DRAFT);
        this.title = title;
        this.description = description;
        this.startAt = startAt;
        this.endAt = endAt;
        this.winnerCount = winnerCount;
        this.drawMethod = drawMethod;
    }

    /** 초안 Event에 논리 삭제 시각을 기록한다. */
    public void delete() {
        requireStatus(EventStatus.DRAFT);
        deletedAt = LocalDateTime.now(ZoneOffset.UTC);
    }

    /** 허용된 현재 상태인지 검증한다. */
    private void requireStatus(EventStatus expected) {
        // 상태 전이 전제조건을 aggregate 내부에서 일관되게 검증한다.
        if (status != expected) {
            throw new BusinessException(EventErrorCode.INVALID_STATE);
        }
    }
}
