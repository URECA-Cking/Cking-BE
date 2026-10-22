package kr.co.cking.subscriptionverification.domain;

import kr.co.cking.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@RequiredArgsConstructor
public enum SubscriptionVerificationErrorCode implements ErrorCode {

    INVALID_VERIFICATION_IMAGE(HttpStatus.BAD_REQUEST, "인증 이미지가 유효하지 않습니다."),
    VERIFICATION_NOT_FOUND(HttpStatus.NOT_FOUND, "구독 인증을 찾을 수 없습니다."),
    CREATOR_CHANNEL_NOT_CONFIGURED(HttpStatus.CONFLICT, "Creator YouTube 채널이 설정되지 않았습니다."),
    INVALID_VERIFICATION_MISSION(HttpStatus.CONFLICT, "구독 인증 대상 미션이 아닙니다."),
    VERIFICATION_IN_PROGRESS(HttpStatus.CONFLICT, "이미 처리 중인 구독 인증이 있습니다."),
    VERIFICATION_ALREADY_APPROVED(HttpStatus.CONFLICT, "이미 구독 인증을 완료했습니다."),
    IDEMPOTENCY_CONFLICT(HttpStatus.CONFLICT, "동일 requestId로 다른 요청 내용이 전달되었습니다."),
    VERIFICATION_SUBMISSION_LIMIT_EXCEEDED(
            HttpStatus.TOO_MANY_REQUESTS,
            "구독 인증 제출 한도를 초과했습니다."),
    VERIFICATION_UNAVAILABLE(
            HttpStatus.SERVICE_UNAVAILABLE,
            "구독 인증 제출을 일시적으로 이용할 수 없습니다."),
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
