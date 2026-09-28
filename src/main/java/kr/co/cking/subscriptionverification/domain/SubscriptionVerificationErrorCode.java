package kr.co.cking.subscriptionverification.domain;

import kr.co.cking.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@RequiredArgsConstructor
public enum SubscriptionVerificationErrorCode implements ErrorCode {

    INVALID_VERIFICATION_IMAGE(HttpStatus.BAD_REQUEST, "인증 이미지가 유효하지 않습니다."),
    CREATOR_CHANNEL_NOT_CONFIGURED(HttpStatus.CONFLICT, "Creator YouTube 채널이 설정되지 않았습니다."),
    CHANNEL_HANDLE_CONFLICT(HttpStatus.CONFLICT, "이미 사용 중인 YouTube 채널 handle입니다."),
    INVALID_STATE(HttpStatus.CONFLICT, "현재 상태에서 허용되지 않는 작업입니다.");

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
