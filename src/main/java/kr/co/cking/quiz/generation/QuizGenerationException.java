package kr.co.cking.quiz.generation;

/** AI 퀴즈 생성 엔진 내부 오류의 공통 상위 타입이다. */
public class QuizGenerationException extends RuntimeException {

    public QuizGenerationException(String message) {
        super(message);
    }

    public QuizGenerationException(String message, Throwable cause) {
        super(message, cause);
    }
}
