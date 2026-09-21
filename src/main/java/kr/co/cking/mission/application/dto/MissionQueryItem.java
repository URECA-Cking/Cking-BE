package kr.co.cking.mission.application.dto;

import kr.co.cking.mission.domain.MissionType;

import java.time.Instant;

/** GET /api/creators/{creatorId}/missions의 항목. */
public record MissionQueryItem(
        Long missionId,
        MissionType type,
        Integer rewardAmount,
        Instant activeFrom,
        Instant activeTo,
        boolean completedToday
) {
}
