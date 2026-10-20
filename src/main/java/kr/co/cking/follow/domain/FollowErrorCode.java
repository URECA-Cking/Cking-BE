package kr.co.cking.follow.domain;

import kr.co.cking.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@RequiredArgsConstructor
public enum FollowErrorCode implements ErrorCode {
    SELF_FOLLOW_NOT_ALLOWED(HttpStatus.BAD_REQUEST, "본인 크리에이터는 팔로우할 수 없습니다."),
    FOLLOW_REQUIRED(HttpStatus.FORBIDDEN, "팔로우한 크리에이터만 읽음 처리할 수 있습니다.");

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
