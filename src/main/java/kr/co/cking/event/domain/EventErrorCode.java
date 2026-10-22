package kr.co.cking.event.domain;

import kr.co.cking.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@RequiredArgsConstructor
public enum EventErrorCode implements ErrorCode {

    EVENT_NOT_FOUND(HttpStatus.NOT_FOUND, "이벤트를 찾을 수 없습니다."),
    INVALID_STATE(HttpStatus.CONFLICT, "현재 상태에서는 수행할 수 없습니다."),
    APPROVAL_REQUEST_TOO_LATE(HttpStatus.CONFLICT, "이벤트 시작 최소 48시간 전까지 개설 승인을 요청해야 합니다."),
    IDEMPOTENCY_CONFLICT(HttpStatus.CONFLICT, "동일 요청 ID에 다른 요청 내용이 전달되었습니다."),
    CONCURRENT_COMMAND(HttpStatus.CONFLICT, "동일 이벤트에 대한 명령이 충돌했습니다."),
    INVALID_EVENT_BANNER_IMAGE(HttpStatus.BAD_REQUEST, "사용할 수 없는 이벤트 배너 이미지입니다.");

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
