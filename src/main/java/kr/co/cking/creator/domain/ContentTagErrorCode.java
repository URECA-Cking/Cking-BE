package kr.co.cking.creator.domain;

import kr.co.cking.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@RequiredArgsConstructor
public enum ContentTagErrorCode implements ErrorCode {
    CONTENT_TAXONOMY_UNAVAILABLE(HttpStatus.CONFLICT, "승인된 콘텐츠 분류체계가 없습니다."),
    CONTENT_INPUT_CONFLICT(HttpStatus.CONFLICT, "입력 또는 분류 실행 세대가 변경되었습니다."),
    CONTENT_PAYLOAD_CONFLICT(HttpStatus.CONFLICT, "동일 결과 키에 다른 내용이 전달되었습니다."),
    INVALID_CONTENT_TAG(HttpStatus.BAD_REQUEST, "콘텐츠 태그 계약이 올바르지 않습니다."),
    CONTENT_LEASE_CONFLICT(HttpStatus.CONFLICT, "변경 작업의 처리 세대 또는 lease가 만료되었습니다.");
    private final HttpStatus status;
    private final String message;
    public String code() { return name(); }
    public HttpStatus status() { return status; }
    public String message() { return message; }
}
