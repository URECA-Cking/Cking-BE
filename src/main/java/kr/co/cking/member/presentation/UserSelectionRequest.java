package kr.co.cking.member.presentation;

import jakarta.validation.constraints.NotNull;

public record UserSelectionRequest(@NotNull Long userId) {
}
