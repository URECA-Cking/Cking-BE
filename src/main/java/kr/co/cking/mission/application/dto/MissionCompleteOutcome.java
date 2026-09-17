package kr.co.cking.mission.application.dto;

import kr.co.cking.ticket.application.dto.EarnResultCode;

import java.time.LocalDateTime;

/**
 * 미션 완료 성공 계열(EARN_ACCEPTED/ALREADY_PROCESSED) 결과. 실패 코드는
 * {@link kr.co.cking.mission.domain.MissionErrorCode}를 실은 {@code BusinessException}으로
 * 던지므로 이 레코드에는 담기지 않는다.
 */
public record MissionCompleteOutcome(
        EarnResultCode code,
        Long missionId,
        Integer rewardAmount,
        LocalDateTime completedAt
) {
}
