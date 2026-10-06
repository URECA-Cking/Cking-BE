package kr.co.cking.abuse.domain;

import kr.co.cking.common.exception.ErrorCode;
import org.springframework.http.HttpStatus;

/** Abuse Detection 검토 상태 전이에서 사용하는 업무 오류 코드다. */
public enum AbuseDetectionErrorCode implements ErrorCode {
    INVALID_STATE(HttpStatus.CONFLICT, "현재 Detection 상태에서는 검토할 수 없습니다.");

    private final HttpStatus status;
    private final String message;

    /** 오류 코드별 HTTP 상태와 사용자 메시지를 초기화한다. */
    AbuseDetectionErrorCode(HttpStatus status, String message) {
        this.status = status;
        this.message = message;
    }

    /** API 응답에 노출할 안정적인 업무 오류 코드를 반환한다. */
    @Override
    public String code() {
        return name();
    }

    /** 업무 오류에 대응하는 HTTP 상태를 반환한다. */
    @Override
    public HttpStatus status() {
        return status;
    }

    /** 호출자에게 보여 줄 상태 전이 오류 메시지를 반환한다. */
    @Override
    public String message() {
        return message;
    }
}
