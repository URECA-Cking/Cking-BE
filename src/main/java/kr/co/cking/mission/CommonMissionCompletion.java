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
 * 공용 미션 완료 기록. {@link kr.co.cking.mission.MissionCompletion}과 같은 계약
 * ({@code uk_common_completion_request}가 재전달 멱등성, {@code uk_common_completion_business}가
 * 같은 날 중복 보상 방어)을 쓰되 {@code creatorId}가 없다(이슈 #219).
 */
@Entity
@Table(name = "common_mission_completion")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CommonMissionCompletion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    private Long completionId;

    private Long memberId;
    private Long missionId;
    private String periodKey;
    private String requestId;
    private String payloadFingerprint;
    private Instant completedAt;

    @Builder
    private CommonMissionCompletion(Long memberId, Long missionId, String periodKey,
                                     String requestId, String payloadFingerprint, Instant completedAt) {
        this.memberId = memberId;
        this.missionId = missionId;
        this.periodKey = periodKey;
        this.requestId = requestId;
        this.payloadFingerprint = payloadFingerprint;
        this.completedAt = completedAt;
    }
}
