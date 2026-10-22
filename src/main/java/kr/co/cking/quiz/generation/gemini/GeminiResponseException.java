package kr.co.cking.quiz.generation.gemini;

import kr.co.cking.quiz.generation.QuizProviderException;

/** Gemini 응답 envelope가 예상한 구조가 아닐 때 발생한다. */
public class GeminiResponseException extends QuizProviderException {

    public GeminiResponseException(String message) {
        super(message);
    }

    public GeminiResponseException(String message, Throwable cause) {
        super(message, cause);
    }
}
