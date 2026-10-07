package kr.co.cking.interest.domain;

import kr.co.cking.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/** 관심 분야 추천 결과 적재 오류다. 코드 문자열은 유사 추천 결과 적재(Creator)와 같은 의미로 맞춘다. */
@RequiredArgsConstructor
public enum InterestErrorCode implements ErrorCode {
    INVALID_RECOMMENDATION_RESULT(HttpStatus.BAD_REQUEST, "관심 분야 추천 결과 묶음이 올바르지 않습니다."),
    RECOMMENDATION_INPUT_CONFLICT(HttpStatus.CONFLICT, "같은 적용 실행 번호에 다른 추천 결과가 전달되었습니다."),
    STALE_RECOMMENDATION_INPUT(HttpStatus.CONFLICT, "현재보다 오래된 추천 적용 실행입니다.");

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
