package kr.co.cking.mission;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import kr.co.cking.mission.domain.MissionType;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 크리에이터별 미션 정의. 1차 MVP는 크리에이터당 유형별로 미션이 하나만 존재한다
 * ({@code uk_mission_creator_type}).
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
    @EqualsAndHashCode.Include
    private Long missionId;

    private Long creatorId;

    @Enumerated(EnumType.STRING)
    private MissionType type;

    private Integer rewardAmount;
    private Instant activeFrom;
    private Instant activeTo;
    private Instant createdAt;

    public Mission(Long creatorId, MissionType type, Integer rewardAmount, Instant activeFrom, Instant activeTo) {
        if (rewardAmount == null || rewardAmount <= 0) {
            throw new IllegalArgumentException("rewardAmount는 0보다 커야 합니다.");
        }
        this.creatorId = creatorId;
        this.type = type;
        this.rewardAmount = rewardAmount;
        this.activeFrom = activeFrom;
        this.activeTo = activeTo;
        this.createdAt = Instant.now();
    }

    @PrePersist
    private void assignCreatedAtIfMissing() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    /**
     * {@code activeFrom}/{@code activeTo}가 비어 있으면 상시 활성으로 간주한다.
     */
    public boolean isActiveAt(Instant now) {
        if (activeFrom != null && now.isBefore(activeFrom)) {
            return false;
        }
        return activeTo == null || !now.isAfter(activeTo);
    }
}
