package kr.co.cking.event;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
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
    private Event(Long creatorId, String requestId, String title, String description, Instant startAt, Instant endAt,
                  Integer winnerCount, String drawMethod, EventStatus status, Long createdBy, Instant createdAt) {
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

    /**
     * API 명세 §4.1 displayStatus 규칙. SCHEDULED는 시각 무관 UPCOMING,
     * CLOSING 이후 상태는 시각 무관 CLOSED — OPEN만 endAt과 비교가 필요하다.
     */
    public DisplayStatus displayStatus(Instant now) {
        return DisplayStatus.of(status, endAt, now);
    }
}
