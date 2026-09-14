package kr.co.cking.common.exception;

import kr.co.cking.common.response.ErrorCode;
import lombok.Getter;

/**
 * 업무 규칙 위반 예외.
 *
 * <p>명세 5.4의 결과 코드 중 {@code SUCCESS}, {@code DUPLICATE_REPLAY}처럼
 * 실패가 아닌 코드는 이 예외로 던지지 않는다. 정상 응답으로 반환한다.
 */
@Getter
public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;

    public BusinessException(ErrorCode errorCode) {
        super(errorCode.message());
        this.errorCode = errorCode;
    }

    public BusinessException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }
}
