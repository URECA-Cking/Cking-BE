package kr.co.cking.abuse.application;

import java.time.Instant;
import java.util.UUID;
import kr.co.cking.mission.domain.CommonMissionType;
import kr.co.cking.mission.domain.MissionType;

/** 검증된 Mission이 EARN period를 확정한 시점의 관찰용 scalar context다. */
public record MissionObservationContext(
        Long userId,
        Long creatorId,
        Long missionId,
        UUID requestId,
        Instant requestedAt,
        MissionType creatorType,
        CommonMissionType commonType
) {
    public static MissionObservationContext creator(
            Long userId, Long creatorId, Long missionId, UUID requestId, Instant requestedAt, MissionType type
    ) {
        return new MissionObservationContext(userId, creatorId, missionId, requestId, requestedAt, type, null);
    }

    public static MissionObservationContext common(
            Long userId, Long missionId, UUID requestId, Instant requestedAt, CommonMissionType type
    ) {
        return new MissionObservationContext(userId, null, missionId, requestId, requestedAt, null, type);
    }
}
