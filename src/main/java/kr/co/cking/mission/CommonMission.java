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
import kr.co.cking.mission.domain.CommonMissionType;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 크리에이터에 묶이지 않는 공용 미션 정의(이슈 #219). {@link Mission}과 같은 계약
 * ({@code isActiveAt}, reward 검증)을 쓰지만, {@code creator_id}가 없어 별도
 * 테이블·엔티티로 분리했다 — 공용은 크리에이터가 아니다.
 */
@Entity
@Table(
        name = "common_mission",
        uniqueConstraints = @UniqueConstraint(name = "uk_common_mission_type", columnNames = "type")
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CommonMission {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    private Long missionId;

    @Enumerated(EnumType.STRING)
    private CommonMissionType type;

    private Integer rewardAmount;
    private Instant activeFrom;
    private Instant activeTo;
    private Instant createdAt;

    public CommonMission(CommonMissionType type, Integer rewardAmount, Instant activeFrom, Instant activeTo) {
        if (rewardAmount == null || rewardAmount <= 0) {
            throw new IllegalArgumentException("rewardAmount는 0보다 커야 합니다.");
        }
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

    /** {@link Mission#isActiveAt}와 동일 계약: activeFrom <= now < activeTo, activeTo는 배제(exclusive). */
    public boolean isActiveAt(Instant now) {
        if (activeFrom != null && now.isBefore(activeFrom)) {
            return false;
        }
        return activeTo == null || now.isBefore(activeTo);
    }
}
