package kr.co.cking.notification.domain;

import kr.co.cking.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/** Notification 도메인에서 발생하는 업무 오류를 정의한다. */
@RequiredArgsConstructor
public enum NotificationErrorCode implements ErrorCode {

    NOTIFICATION_NOT_FOUND(HttpStatus.NOT_FOUND, "알림을 찾을 수 없습니다.");

    private final HttpStatus status;
    private final String message;

    /** 오류 코드 문자열을 반환한다. */
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
