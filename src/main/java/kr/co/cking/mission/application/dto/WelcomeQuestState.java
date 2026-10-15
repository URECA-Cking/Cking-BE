package kr.co.cking.mission.application.dto;

import java.util.List;

/** 현재 회원의 웰컴 퀘스트 대상 여부와 단계별 상태다. */
public record WelcomeQuestState(boolean eligible, boolean completed, List<WelcomeQuestItem> items,
                                WelcomeQuestItem bonus) {
}
