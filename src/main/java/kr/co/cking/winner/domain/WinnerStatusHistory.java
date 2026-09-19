package kr.co.cking.winner.domain;

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

/** WinnerManagement 상태 전이의 주체와 시각을 보존하는 변경 이력이다. */
@Getter
@Entity
@Table(name = "winner_status_history")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WinnerStatusHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "winner_management_id", nullable = false, updatable = false)
    private Long winnerManagementId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30, updatable = false)
    private WinnerManagementStatus status;

    @Column(name = "changed_by", nullable = false, updatable = false)
    private Long changedBy;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** 상태 전이 결과와 변경 주체를 이력으로 만든다. */
    public static WinnerStatusHistory create(
            Long winnerManagementId,
            WinnerManagementStatus status,
            Long changedBy
    ) {
        requirePositive(winnerManagementId, "winnerManagementId");
        requirePositive(changedBy, "changedBy");

        WinnerStatusHistory history = new WinnerStatusHistory();
        history.winnerManagementId = winnerManagementId;
        history.status = status;
        history.changedBy = changedBy;
        return history;
    }
}
