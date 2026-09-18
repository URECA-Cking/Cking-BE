package kr.co.cking.drawing.domain;

import kr.co.cking.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@RequiredArgsConstructor
public enum DrawingErrorCode implements ErrorCode {

    DRAWING_NOT_FOUND(HttpStatus.NOT_FOUND, "추첨을 찾을 수 없습니다."),
    DRAWING_SEED_NOT_FOUND(HttpStatus.NOT_FOUND, "추첨 Seed를 찾을 수 없습니다."),
    INVALID_STATE(HttpStatus.CONFLICT, "현재 Drawing 상태에서는 수행할 수 없습니다."),
    CONCURRENT_COMMAND(HttpStatus.CONFLICT, "동일 Event의 INITIAL Drawing 명령이 진행 중입니다."),
    DRAWING_NOT_COMPLETED(HttpStatus.CONFLICT, "완료된 추첨만 결과를 조회할 수 있습니다.");

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
