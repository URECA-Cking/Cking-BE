package kr.co.cking.quiz.presentation.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import kr.co.cking.quiz.domain.QuizRevisionDecision;

public final class CreatorQuizRequest {

    private CreatorQuizRequest() {
    }

    /** @param reason 선택 거절 사유 */
    public record Reject(@Size(max = QuizRevisionDecision.MAX_REASON_LENGTH) String reason) {
    }

    /** @param revisionId 게시할 revision. 최신 revision이어야 한다 */
    public record Publish(@NotNull @Positive Long revisionId) {
    }
}
