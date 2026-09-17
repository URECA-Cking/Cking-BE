package kr.co.cking.event.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import kr.co.cking.common.exception.BusinessException;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Entity
@Table(name = "event")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Event {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long eventId;

    private Long creatorId;
    private String requestId;
    private String title;
    private String description;
    private Instant startAt;
    private Instant endAt;
    private Integer winnerCount;
    private String drawMethod;

    @Enumerated(EnumType.STRING)
    private EventStatus status;

    private String cutoffStreamId;
    private Instant closedAt;
    private Instant publishedAt;
    private Instant deletedAt;
    private Long createdBy;

    @Column(updatable = false)
    private Instant createdAt;

    @Builder
    private Event(
            Long creatorId,
            String requestId,
            String title,
            String description,
            Instant startAt,
            Instant endAt,
            Integer winnerCount,
            String drawMethod,
            EventStatus status,
            Long createdBy,
            Instant createdAt
    ) {
        this.creatorId = creatorId;
        this.requestId = requestId;
        this.title = title;
        this.description = description;
        this.startAt = startAt;
        this.endAt = endAt;
        this.winnerCount = winnerCount;
        this.drawMethod = drawMethod;
        this.status = status;
        this.createdBy = createdBy;
        this.createdAt = createdAt;
    }

    /** Creator가 작성한 새 Event를 초안 상태로 생성한다. */
    public Event(
            Long creatorId,
            String title,
            String description,
            Instant startAt,
            Instant endAt,
            int winnerCount,
            DrawMethod drawMethod,
            Long createdBy,
            String requestId
    ) {
        this.creatorId = creatorId;
        this.requestId = requestId;
        this.title = title;
        this.description = description;
        this.startAt = startAt;
        this.endAt = endAt;
        this.winnerCount = winnerCount;
        this.drawMethod = drawMethod.name();
        this.status = EventStatus.DRAFT;
        this.createdBy = createdBy;
        this.createdAt = Instant.now();
    }

    public void requestApproval() {
        requireStatus(EventStatus.DRAFT);
        status = EventStatus.PENDING_APPROVAL;
    }

    public void approve() {
        requireStatus(EventStatus.PENDING_APPROVAL);
        status = EventStatus.SCHEDULED;
    }

    public void open() {
        requireStatus(EventStatus.SCHEDULED);
        status = EventStatus.OPEN;
    }

    /** 마감된 Event를 초기 추첨 완료 상태로 전이한다. */
    public void completeDrawing() {
        requireStatus(EventStatus.CLOSED);
        status = EventStatus.DRAW_COMPLETED;
    }

    /** 추첨이 완료된 Event를 결과 공개 상태로 전이한다. */
    public void publish() {
        requireStatus(EventStatus.DRAW_COMPLETED);
        status = EventStatus.PUBLISHED;
    }

    public void reject() {
        requireStatus(EventStatus.PENDING_APPROVAL);
        status = EventStatus.REJECTED;
    }

    public void changeToDraft() {
        requireStatus(EventStatus.REJECTED);
        status = EventStatus.DRAFT;
    }

    public void update(
            String title,
            String description,
            Instant startAt,
            Instant endAt,
            int winnerCount,
            DrawMethod drawMethod
    ) {
        requireStatus(EventStatus.DRAFT);
        this.title = title;
        this.description = description;
        this.startAt = startAt;
        this.endAt = endAt;
        this.winnerCount = winnerCount;
        this.drawMethod = drawMethod.name();
    }

    public void delete() {
        if (status != EventStatus.DRAFT && status != EventStatus.REJECTED) {
            throw new BusinessException(EventErrorCode.INVALID_STATE);
        }
        deletedAt = Instant.now();
    }

    public DisplayStatus displayStatus(Instant now) {
        return DisplayStatus.of(status, endAt, now);
    }

    @PrePersist
    void prePersist() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    private void requireStatus(EventStatus expected) {
        if (status != expected) {
            throw new BusinessException(EventErrorCode.INVALID_STATE);
        }
    }
}
