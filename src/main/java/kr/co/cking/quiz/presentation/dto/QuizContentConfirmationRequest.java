package kr.co.cking.quiz.presentation.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import kr.co.cking.quiz.grounding.VideoGroundingValidator;

/**
 * Creator가 검토한 Grounding 원문 확정 요청(이슈 #615). 원문은 정규화하지 않고 그대로 저장한다.
 *
 * @param contentText 비어 있지 않은 50,000자 이하 원문
 */
public record QuizContentConfirmationRequest(
        @NotBlank @Size(max = VideoGroundingValidator.MAX_CONTENT_TEXT_LENGTH) String contentText
) {
}
