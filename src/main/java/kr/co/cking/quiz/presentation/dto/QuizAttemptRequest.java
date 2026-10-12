package kr.co.cking.quiz.presentation.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import kr.co.cking.quiz.application.dto.QuizAttemptCommand;
import java.util.List;
import java.util.UUID;

public record QuizAttemptRequest(@NotNull UUID requestId, @NotNull @Positive Long revisionId,
        @NotEmpty List<@NotNull @Valid Answer> answers) {
    public record Answer(@NotNull @PositiveOrZero Integer questionNo,
            @NotNull @PositiveOrZero Integer selectedOptionIndex) {}
    public QuizAttemptCommand toCommand(Long memberId, Long creatorId, Long quizId) {
        return new QuizAttemptCommand(memberId, creatorId, quizId, requestId, revisionId,
                answers.stream().map(a -> new QuizAttemptCommand.Answer(a.questionNo(), a.selectedOptionIndex())).toList());
    }
}
