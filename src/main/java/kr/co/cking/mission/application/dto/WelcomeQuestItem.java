package kr.co.cking.mission.application.dto;

import kr.co.cking.mission.domain.WelcomeQuestStatus;
import kr.co.cking.mission.domain.WelcomeQuestStep;

/** 웰컴 보상 하나의 조회·지급 응답이다. */
public record WelcomeQuestItem(WelcomeQuestStep step, int rewardAmount, WelcomeQuestStatus status) {
}
