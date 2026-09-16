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

/** Event의 차수별 승인 요청과 심사 이력을 보존한다. */
@Entity
@Table(name = "event_approval_request")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class EventApprovalRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_id", nullable = false)
    private Long eventId;

    @Column(name = "approval_round", nullable = false)
    private int approvalRound;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private EventApprovalRequestStatus status;

    @Column(name = "requested_by", nullable = false)
    private Long requestedBy;

    @Column(name = "requested_at", nullable = false)
    private LocalDateTime requestedAt;

    @Column(name = "reviewed_by")
    private Long reviewedBy;

    @Column(name = "reviewed_at")
    private LocalDateTime reviewedAt;

    @Column(name = "reject_reason", length = 500)
    private String rejectReason;

    /** 새 승인 요청을 PENDING 상태와 요청 시각으로 생성한다. */
    public EventApprovalRequest(Long eventId, int approvalRound, Long requestedBy) {
        this.eventId = eventId;
        this.approvalRound = approvalRound;
        this.status = EventApprovalRequestStatus.PENDING;
        this.requestedBy = requestedBy;
        this.requestedAt = LocalDateTime.now(ZoneOffset.UTC);
    }

    /** 대기 중인 승인 요청에 승인 심사 정보를 기록한다. */
    public void approve(Long reviewerId) {
        requirePending();
        status = EventApprovalRequestStatus.APPROVED;
        reviewedBy = reviewerId;
        reviewedAt = LocalDateTime.now(ZoneOffset.UTC);
    }

    /** 대기 중인 승인 요청에 거절 심사 정보와 거절 사유를 기록한다. */
    public void reject(Long reviewerId, String reason) {
        requirePending();
        status = EventApprovalRequestStatus.REJECTED;
        reviewedBy = reviewerId;
        reviewedAt = LocalDateTime.now(ZoneOffset.UTC);
        rejectReason = reason;
    }

    /** 심사 가능한 대기 상태인지 검증한다. */
    private void requirePending() {
        if (status != EventApprovalRequestStatus.PENDING) {
            throw new BusinessException(EventErrorCode.INVALID_STATE);
        }
    }
}
