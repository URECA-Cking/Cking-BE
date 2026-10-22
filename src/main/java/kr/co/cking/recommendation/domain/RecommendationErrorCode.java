package kr.co.cking.recommendation.domain;

import kr.co.cking.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@RequiredArgsConstructor
public enum RecommendationErrorCode implements ErrorCode {
    INVALID_RECOMMENDATION_RESULT(HttpStatus.BAD_REQUEST, "추천 결과 묶음의 자격 근거가 올바르지 않습니다.");

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
