package kr.co.cking.quiz.application.dto;

import kr.co.cking.quiz.grounding.GroundingFact;

import java.util.List;

/**
 * Creator의 진행 중인 Quiz 생성 작업(이슈 #615). 원문 검토 대기일 때만 검토할 AI 원문을 함께 담는다.
 *
 * @param content 원문 검토 대기(AWAITING_REVIEW)일 때만 있다
 */
public record QuizGenerationCurrentView(QuizGenerationView generation, Content content) {

    /**
     * Creator가 검토할 Grounding 결과.
     *
     * @param maxLength 확정할 수 있는 원문의 최대 길이(Java {@code String.length()})
     */
    public record Content(String aiContentText, List<GroundingFact> facts, String groundingModel,
                          String groundingPromptVersion, int maxLength) {

        public Content {
            facts = List.copyOf(facts);
        }
    }
}
