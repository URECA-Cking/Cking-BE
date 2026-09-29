package kr.co.cking.subscriptionverification.infrastructure.deepseek;

/** DeepSeek Provider 응답 envelope 또는 분석 JSON이 계약을 만족하지 않을 때 발생한다. */
final class DeepSeekResponseParseException extends RuntimeException {

    DeepSeekResponseParseException(String message) {
        super(message);
    }

    DeepSeekResponseParseException(String message, Throwable cause) {
        super(message, cause);
    }
}
