package kr.co.cking.mission.presentation.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/** requestId는 필수이며 answer는 QUIZ 단계에만 허용된다. */
public record WelcomeQuestClaimRequest(@NotNull UUID requestId, Boolean answer) {
}
