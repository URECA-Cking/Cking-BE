package kr.co.cking.redraw.domain;

import kr.co.cking.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/** RedrawRequest 생성·관리에서 사용하는 업무 오류 코드다. */
@RequiredArgsConstructor
public enum RedrawErrorCode implements ErrorCode {

    REDRAW_REQUEST_NOT_FOUND(HttpStatus.NOT_FOUND, "재추첨 요청을 찾을 수 없습니다."),
    INVALID_STATE(HttpStatus.CONFLICT, "현재 상태에서는 재추첨 요청을 만들 수 없습니다."),
    NO_REDRAW_VACANCY(HttpStatus.CONFLICT, "재추첨할 미처리 결원이 없습니다."),
    IDEMPOTENCY_CONFLICT(HttpStatus.CONFLICT, "동일 idempotencyKey에 다른 요청 내용이 전달되었습니다."),
    CONCURRENT_COMMAND(HttpStatus.CONFLICT, "동일 재추첨 요청 생성 명령이 충돌했습니다.");

    private final HttpStatus status;
    private final String message;

    /** 응답 본문에 노출할 오류 코드 문자열을 반환한다. */
    @Override
    public String code() {
        return name();
    }

    /** 오류에 대응하는 HTTP 상태를 반환한다. */
    @Override
    public HttpStatus status() {
        return status;
    }

    /** 호출자에게 표시할 오류 메시지를 반환한다. */
    @Override
    public String message() {
        return message;
    }
}
