package kr.co.cking.subscriptionverification.infrastructure.gemini;

/** Gemini Provider 응답 envelope 또는 분석 JSON이 계약을 만족하지 않을 때 발생한다. */
final class GeminiResponseParseException extends RuntimeException {

    GeminiResponseParseException(String message, Throwable cause) {
        super(message, cause);
    }
}
