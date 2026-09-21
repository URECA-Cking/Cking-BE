package kr.co.cking.winner.domain;

import kr.co.cking.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/** Winner 도메인 명령과 조회에서 사용하는 업무 오류 코드다. */
@RequiredArgsConstructor
public enum WinnerErrorCode implements ErrorCode {

    WINNER_NOT_FOUND(HttpStatus.NOT_FOUND, "당첨 결과를 찾을 수 없습니다."),
    WINNER_MANAGEMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "당첨 운영 정보를 찾을 수 없습니다."),
    INVALID_STATE(HttpStatus.CONFLICT, "현재 당첨 상태에서는 수행할 수 없습니다.");

    private final HttpStatus status;
    private final String message;

    /** 응답 본문에 사용할 오류 코드 문자열을 반환한다. */
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
