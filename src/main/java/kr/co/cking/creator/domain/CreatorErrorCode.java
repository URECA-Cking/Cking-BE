package kr.co.cking.creator.domain;

import kr.co.cking.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@RequiredArgsConstructor
public enum CreatorErrorCode implements ErrorCode {
    INVALID_STATE(HttpStatus.CONFLICT, "현재 상태에서는 수행할 수 없습니다."),
    CONCURRENT_COMMAND(HttpStatus.CONFLICT, "동일 신청에 대한 명령이 충돌했습니다.");

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
