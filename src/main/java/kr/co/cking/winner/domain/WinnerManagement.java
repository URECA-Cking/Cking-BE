package kr.co.cking.winner.domain;

import static kr.co.cking.common.validation.DomainValidator.requirePositive;

import kr.co.cking.common.exception.BusinessException;
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
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

@Getter
@Entity
@Table(
        name = "winner_management",
        uniqueConstraints = @UniqueConstraint(name = "uk_winner_mgmt_winner", columnNames = "winner_id")
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WinnerManagement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "winner_id", nullable = false, updatable = false)
    private Long winnerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private WinnerManagementStatus status;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public static WinnerManagement selected(Long winnerId) {
        requirePositive(winnerId, "winnerId");

        WinnerManagement management = new WinnerManagement();
        management.winnerId = winnerId;
        management.status = WinnerManagementStatus.SELECTED;
        return management;
    }

    /** SELECTED 상태의 Winner를 당첨 포기 종결 상태로 변경한다. */
    public void decline() {
        if (status != WinnerManagementStatus.SELECTED) {
            throw new BusinessException(WinnerErrorCode.INVALID_STATE);
        }
        status = WinnerManagementStatus.DECLINED;
    }
}
