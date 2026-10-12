package kr.co.cking.quiz.grounding;

/**
 * YouTube 영상을 분석해 Quiz 생성 입력(contentText)과 근거(facts)를 만든다(이슈 #524).
 *
 * <p>호출자는 이미 검증·추출한 11자 videoId를 넘긴다. 사용자 URL을 받지 않는다.
 * 실패는 {@link VideoGroundingException}, 시간 초과는
 * {@link kr.co.cking.quiz.generation.QuizProviderTimeoutException}이며 둘 다 자동 재호출 대상이 아니다.
 */
public interface VideoGrounder {

    VideoGrounding ground(String videoId);
}
