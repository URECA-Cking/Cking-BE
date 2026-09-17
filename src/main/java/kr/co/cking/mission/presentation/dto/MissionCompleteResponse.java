package kr.co.cking.mission.presentation.dto;

import kr.co.cking.mission.application.dto.MissionCompleteOutcome;

import java.time.LocalDateTime;

/** 통합 API 명세 v2.5 §4.2 응답 스키마. {@code status}는 {@code EarnResultCode} 이름을 그대로 노출한다. */
public record MissionCompleteResponse(
        Long missionId,
        Integer rewardAmount,
        LocalDateTime completedAt
) {
    public static MissionCompleteResponse from(MissionCompleteOutcome outcome) {
        return new MissionCompleteResponse(outcome.missionId(), outcome.rewardAmount(), outcome.completedAt());
    }
}
