package kr.co.cking.drawing.domain;

import static kr.co.cking.common.validation.DomainValidator.requirePositive;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** Drawing의 최초 실행과 Retry를 시도 단위로 보존하는 감사 이력이다. */
@Getter
@Entity
@Table(
        name = "draw_attempt_history",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_attempt_drawing_no",
                columnNames = {"drawing_id", "attempt_no"}
        )
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DrawAttemptHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "drawing_id", nullable = false, updatable = false)
    private Long drawingId;

    @Column(name = "attempt_no", nullable = false, updatable = false)
    private int attemptNo;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private DrawAttemptStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "failure_stage", length = 100)
    private DrawingFailureStage failureStage;

    @Column(name = "failure_code", length = 50)
    private String failureCode;

    @Column(name = "failure_message", columnDefinition = "TEXT")
    private String failureMessage;

    @Column(name = "requested_by", updatable = false)
    private Long requestedBy;

    @Column(name = "started_at", nullable = false, updatable = false)
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    public static DrawAttemptHistory started(
            Long drawingId,
            int attemptNo,
            Long requestedBy,
            Instant startedAt
    ) {
        requirePositive(drawingId, "drawingId");
        if (requestedBy != null) {
            requirePositive(requestedBy, "requestedBy");
        }
        if (attemptNo <= 0) {
            throw new IllegalArgumentException("attemptNo는 양수여야 합니다.");
        }
        if (startedAt == null) {
            throw new IllegalArgumentException("startedAt은 필수입니다.");
        }

        DrawAttemptHistory history = new DrawAttemptHistory();
        history.drawingId = drawingId;
        history.attemptNo = attemptNo;
        history.status = DrawAttemptStatus.STARTED;
        history.requestedBy = requestedBy;
        history.startedAt = startedAt;
        return history;
    }

    public void succeed(Instant finishedAt) {
        requireStarted();
        requireFinishedAt(finishedAt);
        this.status = DrawAttemptStatus.SUCCEEDED;
        this.finishedAt = finishedAt;
    }

    public void fail(
            DrawingFailureStage failureStage,
            String failureCode,
            String failureMessage,
            Instant finishedAt
    ) {
        requireStarted();
        if (failureStage == null) {
            throw new IllegalArgumentException("failureStage는 필수입니다.");
        }
        if (failureCode == null || failureCode.isBlank()) {
            throw new IllegalArgumentException("failureCode는 필수입니다.");
        }
        requireFinishedAt(finishedAt);

        this.status = DrawAttemptStatus.FAILED;
        this.failureStage = failureStage;
        this.failureCode = failureCode;
        this.failureMessage = failureMessage;
        this.finishedAt = finishedAt;
    }

    /** 서버 중단으로 종료 시각을 남기지 못한 Attempt를 복구기가 실패로 종결한다. */
    public void interrupt(Instant finishedAt) {
        fail(DrawingFailureStage.SERVER_INTERRUPTED, "SERVER_INTERRUPTED",
                "서버 중단으로 실행이 완료되지 않아 복구 대상으로 전환했습니다.", finishedAt);
    }

    /** 같은 확정 입력으로 다시 실행할 수 없는 입력 단계 실패인지 반환한다. */
    public boolean isRetryable() {
        return status == DrawAttemptStatus.FAILED
                && failureStage != DrawingFailureStage.INPUT_VERIFICATION
                && !"NON_RETRYABLE_FAILURE".equals(failureCode)
                && !"SNAPSHOT_HASH_MISMATCH".equals(failureCode)
                && !"INSUFFICIENT_CANDIDATES".equals(failureCode);
    }

    private void requireStarted() {
        if (status != DrawAttemptStatus.STARTED) {
            throw new IllegalStateException("진행 중인 Attempt만 종료할 수 있습니다.");
        }
    }

    private void requireFinishedAt(Instant finishedAt) {
        if (finishedAt == null || finishedAt.isBefore(startedAt)) {
            throw new IllegalArgumentException("finishedAt은 시작 시각 이후여야 합니다.");
        }
    }
}
