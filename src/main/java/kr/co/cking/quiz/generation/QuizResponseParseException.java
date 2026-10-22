package kr.co.cking.quiz.generation;

public class QuizResponseParseException extends QuizGenerationException {

    public QuizResponseParseException(String message) {
        super(message);
    }

    public QuizResponseParseException(String message, Throwable cause) {
        super(message, cause);
    }
}
