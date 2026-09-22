package kr.co.cking.mission.application.dto;

import kr.co.cking.mission.domain.CommonMissionType;

import java.time.Instant;

/** GET /api/missions의 항목(이슈 #219). {@code MissionQueryItem}과 동일 계약, 크리에이터 축만 없다. */
public record CommonMissionQueryItem(
        Long missionId,
        CommonMissionType type,
        Integer rewardAmount,
        Instant activeFrom,
        Instant activeTo,
        boolean completedToday
) {
}
