package kr.co.cking.mission.presentation.dto;

import kr.co.cking.mission.application.dto.MissionCompleteOutcome;

import java.time.Instant;

/** 통합 API 명세 v2.5 §4.2 응답 스키마. {@code code}는 공통 응답 봉투(ApiResponse)에 싣는다. */
public record MissionCompleteResponse(
        Long missionId,
        Integer rewardAmount,
        Instant completedAt
) {
    public static MissionCompleteResponse from(MissionCompleteOutcome outcome) {
        return new MissionCompleteResponse(outcome.missionId(), outcome.rewardAmount(), outcome.completedAt());
    }
}
