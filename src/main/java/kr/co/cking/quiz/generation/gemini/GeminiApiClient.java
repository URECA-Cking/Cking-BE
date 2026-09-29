package kr.co.cking.quiz.generation.gemini;

/** Gemini API 호출과 Provider 응답 문자열 추출을 담당하는 경계다. */
public interface GeminiApiClient {

    String generateContent(String prompt, int questionCount, int optionCount);
}
