package kr.co.cking.quiz.generation.gemini;

import kr.co.cking.quiz.generation.QuizProviderException;

/** Gemini HTTP/API 호출 실패를 나타낸다. */
public class GeminiApiException extends QuizProviderException {

    public GeminiApiException(String message) {
        super(message);
    }

    public GeminiApiException(String message, Throwable cause) {
        super(message, cause);
    }
}
