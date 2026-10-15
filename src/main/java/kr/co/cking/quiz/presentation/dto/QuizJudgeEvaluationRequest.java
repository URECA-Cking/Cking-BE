package kr.co.cking.quiz.presentation.dto;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record QuizJudgeEvaluationRequest(@NotNull UUID requestId) {}
