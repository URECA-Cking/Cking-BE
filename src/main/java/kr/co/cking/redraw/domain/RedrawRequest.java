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
import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
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

    @Column(name = "completed_at")
    private Instant completedAt;

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

    /** REDRAW Drawing 결과가 원자적으로 확정된 트랜잭션에서 실행 상태를 완료한다. */
    public void completeExecution(Instant completedAt) {
        if (status != RedrawRequestStatus.APPROVED
                || (executionStatus != RedrawExecutionStatus.PENDING
                && executionStatus != RedrawExecutionStatus.FAILED)) {
            throw new IllegalStateException("실행 가능한 재추첨 요청이 아닙니다.");
        }
        if (completedAt == null) {
            throw new IllegalArgumentException("completedAt은 필수입니다.");
        }
        executionStatus = RedrawExecutionStatus.EXECUTED;
        this.completedAt = completedAt;
    }

    /** REDRAW 결과 Transaction 실패를 별도 실패 기록 Transaction에서 보존한다. */
    public void failExecution(Instant failedAt) {
        if (status != RedrawRequestStatus.APPROVED
                || (executionStatus != RedrawExecutionStatus.PENDING
                && executionStatus != RedrawExecutionStatus.FAILED)) {
            throw new IllegalStateException("실패 처리할 수 있는 재추첨 요청이 아닙니다.");
        }
        if (failedAt == null) {
            throw new IllegalArgumentException("failedAt은 필수입니다.");
        }
        executionStatus = RedrawExecutionStatus.FAILED;
        completedAt = failedAt;
    }

    /** 검토 대기 요청을 승인하고 검토자와 검토 시각을 기록한다. */
    public void approve(Long reviewerId) {
        requirePositive(reviewerId, "reviewerId");
        requireRequested();
        status = RedrawRequestStatus.APPROVED;
        reviewedBy = reviewerId;
        reviewedAt = Instant.now();
        rejectReason = null;
    }

    /** 검토 대기 요청을 거절하고 검토자·검토 시각·거절 사유를 기록한다. */
    public void reject(Long reviewerId, String reason) {
        requirePositive(reviewerId, "reviewerId");
        requireValidRejectReason(reason);
        requireRequested();
        status = RedrawRequestStatus.REJECTED;
        reviewedBy = reviewerId;
        reviewedAt = Instant.now();
        rejectReason = reason.strip();
    }

    /** 시스템3 실행을 요청하기 전에 승인·실행 대기 상태인지 검증한다. */
    public void validateExecutable() {
        requireExecutable();
    }

    /** 승인된 요청의 실행 성공 결과를 고정하고 연결된 REDRAW Drawing을 기록한다. */
    public void markExecuted() {
        requireExecutable();
        executionStatus = RedrawExecutionStatus.EXECUTED;
        completedAt = Instant.now();
    }

    /** 후보 부족으로 Drawing을 만들지 못한 실행 결과를 기록한다. */
    public void markInsufficientCandidates() {
        requireExecutable();
        executionStatus = RedrawExecutionStatus.INSUFFICIENT_CANDIDATES;
        completedAt = Instant.now();
    }

    /** 시스템 오류로 끝난 실행 결과를 기록한다. */
    public void markFailed() {
        requireExecutable();
        executionStatus = RedrawExecutionStatus.FAILED;
        completedAt = Instant.now();
    }

    /** 심사 가능한 검토 대기 상태인지 확인하고, 이미 심사된 요청은 거부한다. */
    private void requireRequested() {
        if (status != RedrawRequestStatus.REQUESTED) {
            throw new BusinessException(RedrawErrorCode.INVALID_STATE);
        }
    }

    /** 실행 가능한 승인·대기 상태인지 검증해 중복 실행을 차단한다. */
    private void requireExecutable() {
        if (status != RedrawRequestStatus.APPROVED || executionStatus != RedrawExecutionStatus.PENDING) {
            throw new BusinessException(RedrawErrorCode.INVALID_STATE);
        }
    }

    /** Service가 정규화·검증한 거절 사유가 도메인 저장 한계를 만족하는지 방어한다. */
    private void requireValidRejectReason(String reason) {
        if (reason == null || reason.isBlank() || reason.length() > 500) {
            throw new BusinessException(CommonErrorCode.VALIDATION_FAILED);
        }
    }
}
