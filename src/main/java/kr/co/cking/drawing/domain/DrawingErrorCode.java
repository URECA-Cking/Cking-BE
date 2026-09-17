package kr.co.cking.drawing.domain;

import kr.co.cking.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@RequiredArgsConstructor
public enum DrawingErrorCode implements ErrorCode {

    DRAWING_NOT_FOUND(HttpStatus.NOT_FOUND, "추첨을 찾을 수 없습니다."),
    DRAWING_NOT_COMPLETED(HttpStatus.CONFLICT, "완료된 추첨만 결과를 조회할 수 있습니다."),
    DRAWING_TYPE_NOT_SUPPORTED(HttpStatus.CONFLICT, "이 API는 INITIAL Drawing 공개만 지원합니다.");

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
