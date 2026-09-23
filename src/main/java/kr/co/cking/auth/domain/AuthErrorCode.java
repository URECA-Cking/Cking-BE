package kr.co.cking.auth.domain;

import kr.co.cking.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/** Auth 도메인에서만 사용하는 인증 수단 오류 코드다. */
@RequiredArgsConstructor
public enum AuthErrorCode implements ErrorCode {

    INVALID_LOGIN_CODE(HttpStatus.UNAUTHORIZED, "로그인 코드가 유효하지 않습니다."),
    INVALID_REFRESH_TOKEN(HttpStatus.UNAUTHORIZED, "Refresh Token이 유효하지 않습니다.");

    private final HttpStatus status;
    private final String message;

    /** 외부 응답에 노출할 오류 코드 문자열을 반환한다. */
    @Override
    public String code() {
        return name();
    }

    /** 오류에 대응하는 HTTP 상태를 반환한다. */
    @Override
    public HttpStatus status() {
        return status;
    }

    /** 사용자에게 표시할 오류 메시지를 반환한다. */
    @Override
    public String message() {
        return message;
    }
}
