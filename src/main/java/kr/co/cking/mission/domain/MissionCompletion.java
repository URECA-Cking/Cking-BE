package kr.co.cking.mission.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 미션 완료 이력이자 중복 방지의 최종 방어선.
 *
 * <p>{@code uk_completion_business}(member+creator+mission+period_key)는 새로운
 * requestId로 들어온 업무 중복(DUPLICATE_MISSION)을, {@code uk_completion_request}는
 * 동일 requestId 재전송(ALREADY_PROCESSED)을 막는다. 두 제약 모두 DB 레벨이라
 * 동시 요청에도 정확히 하나만 살아남는다 — FR-P1-014~016.
 */
@Entity
@Table(
        name = "mission_completion",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_completion_business",
                        columnNames = {"member_id", "creator_id", "mission_id", "period_key"}),
                @UniqueConstraint(name = "uk_completion_request", columnNames = "request_id")
        }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MissionCompletion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "completion_id")
    private Long completionId;

    @Column(name = "member_id", nullable = false)
    private Long memberId;

    @Column(name = "creator_id", nullable = false)
    private Long creatorId;

    @Column(name = "mission_id", nullable = false)
    private Long missionId;

    /** UTC 기준 {@code YYYY-MM-DD}. */
    @Column(name = "period_key", nullable = false, length = 10)
    private String periodKey;

    @Column(name = "request_id", nullable = false, length = 36)
    private String requestId;

    @Column(name = "completed_at", nullable = false)
    private LocalDateTime completedAt;

    public MissionCompletion(Long memberId, Long creatorId, Long missionId,
                              String periodKey, UUID requestId, LocalDateTime completedAt) {
        this.memberId = memberId;
        this.creatorId = creatorId;
        this.missionId = missionId;
        this.periodKey = periodKey;
        this.requestId = requestId.toString();
        this.completedAt = completedAt;
    }
}
