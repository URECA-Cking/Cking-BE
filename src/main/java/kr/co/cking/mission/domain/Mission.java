package kr.co.cking.mission.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * 크리에이터별 미션 정의. 1차 MVP는 크리에이터당 유형별로 미션이 하나만 존재한다
 * ({@code uk_mission_creator_type}) — 보상 수량을 바꿀 땐 이 row를 UPDATE한다.
 */
@Entity
@Table(
        name = "mission",
        uniqueConstraints = @UniqueConstraint(name = "uk_mission_creator_type", columnNames = {"creator_id", "type"})
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Mission {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "mission_id")
    private Long missionId;

    @Column(name = "creator_id", nullable = false)
    private Long creatorId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 30)
    private MissionType type;

    @Column(name = "reward_amount", nullable = false)
    private Integer rewardAmount;

    @Column(name = "active_from")
    private LocalDateTime activeFrom;

    @Column(name = "active_to")
    private LocalDateTime activeTo;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    public Mission(Long creatorId, MissionType type, Integer rewardAmount,
                   LocalDateTime activeFrom, LocalDateTime activeTo) {
        if (rewardAmount == null || rewardAmount <= 0) {
            throw new IllegalArgumentException("rewardAmount는 0보다 커야 합니다.");
        }
        this.creatorId = creatorId;
        this.type = type;
        this.rewardAmount = rewardAmount;
        this.activeFrom = activeFrom;
        this.activeTo = activeTo;
        this.createdAt = LocalDateTime.now(ZoneOffset.UTC);
    }

    @PrePersist
    private void assignCreatedAtIfMissing() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now(ZoneOffset.UTC);
        }
    }

    /**
     * {@code activeFrom}/{@code activeTo}가 비어 있으면 상시 활성으로 간주한다.
     * 지금은 두 값을 세팅할 관리 화면이 없어 사실상 항상 true지만, FR-P1-004의
     * "활성 기간 검증" 계약을 위해 미리 구현해 둔다.
     */
    public boolean isActiveAt(LocalDateTime now) {
        if (activeFrom != null && now.isBefore(activeFrom)) {
            return false;
        }
        return activeTo == null || !now.isAfter(activeTo);
    }
}
