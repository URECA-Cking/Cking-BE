package kr.co.cking.creator.domain;

import kr.co.cking.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@RequiredArgsConstructor
public enum CreatorErrorCode implements ErrorCode {
    INVALID_STATE(HttpStatus.CONFLICT, "현재 상태에서는 수행할 수 없습니다."),
    CONCURRENT_COMMAND(HttpStatus.CONFLICT, "동일 대상에 대한 명령이 충돌했습니다."),
    NO_ACTIVE_SPACE_TEMPLATE(HttpStatus.CONFLICT, "활성화된 기본 Creator Space 템플릿이 없습니다."),
    INVALID_ACTIVE_SPACE_TEMPLATE(HttpStatus.CONFLICT, "활성화된 기본 Creator Space 템플릿의 slug 생성 규칙이 올바르지 않습니다."),
    RESERVED_SLUG(HttpStatus.BAD_REQUEST, "사용할 수 없는 slug입니다."),
    SLUG_ALREADY_TAKEN(HttpStatus.CONFLICT, "이미 사용 중인 slug입니다."),
    SLUG_CHANGE_TOO_SOON(HttpStatus.CONFLICT, "slug는 마지막 변경 후 14일이 지나야 다시 바꿀 수 있습니다."),
    INVALID_RECOMMENDATION_RESULT(HttpStatus.BAD_REQUEST, "유사 추천 결과 묶음이 올바르지 않습니다."),
    RECOMMENDATION_INPUT_CONFLICT(HttpStatus.CONFLICT, "같은 적용 실행 번호에 다른 추천 결과가 전달되었습니다."),
    STALE_RECOMMENDATION_INPUT(HttpStatus.CONFLICT, "현재보다 오래된 추천 적용 실행입니다."),
    RECOMMENDATION_REQUEST_NOT_FOUND(HttpStatus.NOT_FOUND, "추천 요청을 찾을 수 없습니다."),
    RECOMMENDATION_REQUEST_EXPIRED(HttpStatus.GONE, "추천 이벤트 수집 기간이 만료되었습니다."),
    RECOMMENDATION_CANDIDATE_NOT_RETURNED(HttpStatus.BAD_REQUEST, "해당 추천 요청에서 반환하지 않은 후보입니다."),
    RECOMMENDATION_EVENT_CONFLICT(HttpStatus.CONFLICT, "같은 이벤트 ID에 다른 내용이 전달되었습니다.");

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
