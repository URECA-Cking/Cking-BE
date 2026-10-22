package kr.co.cking.quiz.generation;

/** 실제 Provider 호출이 제한 시간 안에 끝나지 않았음을 나타낸다. */
public class QuizProviderTimeoutException extends QuizProviderException {

    public QuizProviderTimeoutException(String message, Throwable cause) {
        super(message, cause);
    }
}
