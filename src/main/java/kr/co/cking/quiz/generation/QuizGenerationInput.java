package kr.co.cking.quiz.generation;

/**
 * AI 퀴즈 생성에 필요한 원문과 생성 조건이다.
 *
 * <p>YouTube URL이나 외부 콘텐츠 식별자는 이 계약의 책임이 아니다. 호출자는
 * 이미 확보하고 정제한 텍스트를 전달한다.</p>
 */
public record QuizGenerationInput(
        String contentText,
        int questionCount,
        int optionCount
) {
}
