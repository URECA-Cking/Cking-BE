package kr.co.cking.quiz.presentation.dto;
import kr.co.cking.quiz.application.dto.QuizCompositionCommand;
import java.util.List;
import java.util.UUID;
public record QuizCompositionRequest(UUID requestId, Long baseRevisionId, String baseRevisionSha256,
                                     List<QuizCompositionCommand.Selection> questions) {
    public QuizCompositionCommand toCommand(){return new QuizCompositionCommand(requestId,baseRevisionId,baseRevisionSha256,questions);}
}
