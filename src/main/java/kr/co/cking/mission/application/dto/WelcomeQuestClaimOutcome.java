package kr.co.cking.mission.application.dto;

import kr.co.cking.ticket.application.dto.EarnResultCode;

/** 지급 성공 코드와 받은 보상을 Controller에 전달한다. */
public record WelcomeQuestClaimOutcome(EarnResultCode code, WelcomeQuestItem item) {
}
