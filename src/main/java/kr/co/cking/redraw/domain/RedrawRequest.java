package kr.co.cking.redraw.domain;

import static kr.co.cking.common.validation.DomainValidator.requirePositive;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

/** 서버가 확정한 재추첨 원본 Drawing과 결원 수를 보관하는 요청 엔티티다. */
@Getter
@Entity
@Table(name = "redraw_request")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RedrawRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_id", nullable = false, updatable = false)
    private Long eventId;

    @Column(name = "original_drawing_id", nullable = false, updatable = false)
    private Long originalDrawingId;

    @Column(name = "vacancy_count", nullable = false, updatable = false)
    private int vacancyCount;

    @Column(name = "reason", length = 500, updatable = false)
    private String reason;

    @Column(name = "idempotency_key", nullable = false, updatable = false, unique = true, length = 100)
    private String idempotencyKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private RedrawRequestStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "execution_status", nullable = false, length = 30)
    private RedrawExecutionStatus executionStatus;

    @Column(name = "requested_by", nullable = false, updatable = false)
    private Long requestedBy;

    @CreationTimestamp
    @Column(name = "requested_at", nullable = false, updatable = false)
    private Instant requestedAt;

    @Column(name = "reviewed_by")
    private Long reviewedBy;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    @Column(name = "reject_reason", length = 500)
    private String rejectReason;

    /** 새 요청을 검토 대기·실행 대기 상태로 만들고 서버가 산출한 결원 수를 고정한다. */
    public static RedrawRequest requested(
            Long eventId,
            Long originalDrawingId,
            int vacancyCount,
            String reason,
            String idempotencyKey,
            Long requestedBy
    ) {
        requirePositive(eventId, "eventId");
        requirePositive(originalDrawingId, "originalDrawingId");
        requirePositive(requestedBy, "requestedBy");
        if (vacancyCount <= 0) {
            throw new IllegalArgumentException("vacancyCount는 양수여야 합니다.");
        }

        RedrawRequest request = new RedrawRequest();
        request.eventId = eventId;
        request.originalDrawingId = originalDrawingId;
        request.vacancyCount = vacancyCount;
        request.reason = reason;
        request.idempotencyKey = idempotencyKey;
        request.status = RedrawRequestStatus.REQUESTED;
        request.executionStatus = RedrawExecutionStatus.PENDING;
        request.requestedBy = requestedBy;
        return request;
    }
}
