package kr.co.cking.common.exception;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/**
 * 특정 모듈에 속하지 않는 공통 에러 코드.
 * 도메인 에러는 각 모듈의 enum에 정의한다.
 */
@RequiredArgsConstructor
public enum CommonErrorCode implements ErrorCode {

    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "요청 값이 올바르지 않습니다."),
    /** 도메인 전용 코드가 없을 때의 404 fallback. 가능하면 EVENT_NOT_FOUND 같은 도메인 코드를 쓴다. */
    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "대상을 찾을 수 없습니다."),
    FORBIDDEN(HttpStatus.FORBIDDEN, "권한이 없습니다."),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "지원하지 않는 요청 방식입니다."),
    SYSTEM_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 오류가 발생했습니다.");

    private final HttpStatus status;
    private final String message;

    @Override
    public String code() {
        return name();
    }

    @Override
    public HttpStatus status() {
        return status;
    }

    @Override
    public String message() {
        return message;
    }
}
