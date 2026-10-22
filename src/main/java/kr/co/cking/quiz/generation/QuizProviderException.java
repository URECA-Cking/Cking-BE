package kr.co.cking.quiz.generation;

/** 실제 Provider Adapter에서 외부 API 오류를 감싸기 위한 내부 오류다. */
public class QuizProviderException extends QuizGenerationException {

    public QuizProviderException(String message) {
        super(message);
    }

    public QuizProviderException(String message, Throwable cause) {
        super(message, cause);
    }
}
