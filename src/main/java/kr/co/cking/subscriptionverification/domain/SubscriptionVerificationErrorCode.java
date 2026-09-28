package kr.co.cking.subscriptionverification.domain;

import kr.co.cking.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@RequiredArgsConstructor
public enum SubscriptionVerificationErrorCode implements ErrorCode {

    INVALID_VERIFICATION_IMAGE(HttpStatus.BAD_REQUEST, "인증 이미지가 유효하지 않습니다.");

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
