package kr.co.cking.quiz.presentation.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import kr.co.cking.quiz.application.dto.QuizGenerationCommand;
import kr.co.cking.quiz.domain.QuizRevision;

import java.util.UUID;

/**
 * YouTube 영상 Quiz 생성 요청. 보기 수는 서버가 4로 고정한다.
 *
 * @param questionCount 3 또는 5. 생략하면 3
 */
public record QuizGenerationRequest(
        @NotNull UUID requestId,
        @NotBlank @Size(max = 2048) String youtubeUrl,
        @NotBlank @Size(max = QuizRevision.MAX_TITLE_LENGTH) String title,
        Integer questionCount
) {

    public QuizGenerationCommand toCommand() {
        return new QuizGenerationCommand(requestId, youtubeUrl, title, questionCount);
    }
}
