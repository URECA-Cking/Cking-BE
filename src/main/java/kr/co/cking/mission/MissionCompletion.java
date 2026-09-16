package kr.co.cking.mission;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 미션 완료 기록. {@code uk_completion_request}(requestId)가 EARN Stream 재전달의
 * 멱등성 판정 기준이고, {@code uk_completion_business}(member+creator+mission+period)가
 * 같은 날 중복 보상을 막는 최종 방어선이다(DB 스키마 찐 최종 §5).
 */
@Entity
@Table(name = "mission_completion")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MissionCompletion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    private Long completionId;

    private Long memberId;
    private Long creatorId;
    private Long missionId;
    private String periodKey;
    private String requestId;
    private Instant completedAt;

    @Builder
    private MissionCompletion(Long memberId, Long creatorId, Long missionId, String periodKey,
                               String requestId, Instant completedAt) {
        this.memberId = memberId;
        this.creatorId = creatorId;
        this.missionId = missionId;
        this.periodKey = periodKey;
        this.requestId = requestId;
        this.completedAt = completedAt;
    }
}
