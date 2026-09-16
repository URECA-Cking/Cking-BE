package kr.co.cking.event.domain;

import kr.co.cking.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@RequiredArgsConstructor
public enum EventErrorCode implements ErrorCode {
    INVALID_STATE(HttpStatus.CONFLICT, "현재 상태에서는 수행할 수 없습니다."),
    IDEMPOTENCY_CONFLICT(HttpStatus.CONFLICT, "동일 요청 ID에 다른 요청 내용이 전달되었습니다."),
    CONCURRENT_COMMAND(HttpStatus.CONFLICT, "동일 이벤트에 대한 명령이 충돌했습니다.");

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
