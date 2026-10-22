package kr.co.cking.redraw.domain;

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
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

/** RedrawRequest 실행의 최종 결과와 실패 정보를 감사용으로 보관한다. */
@Entity
@Table(name = "redraw_execution_history")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RedrawExecutionHistory {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "redraw_request_id", nullable = false, updatable = false)
    private Long redrawRequestId;

    @Enumerated(EnumType.STRING)
    @Column(name = "execution_status", nullable = false, updatable = false, length = 30)
    private RedrawExecutionStatus executionStatus;

    @Column(name = "failure_code", updatable = false, length = 50)
    private String failureCode;

    @Column(name = "failure_message", updatable = false, columnDefinition = "TEXT")
    private String failureMessage;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** 최종 실행 상태와 선택적인 실패 원인을 담은 이력 행을 만든다. */
    public static RedrawExecutionHistory of(Long requestId, RedrawExecutionStatus status, String code, String message) {
        RedrawExecutionHistory history = new RedrawExecutionHistory();
        history.redrawRequestId = requestId;
        history.executionStatus = status;
        history.failureCode = code;
        history.failureMessage = message;
        return history;
    }
}
