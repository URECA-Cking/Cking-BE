package kr.co.cking.ticket.domain;

import org.springframework.http.HttpStatus;

import kr.co.cking.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public enum TicketErrorCode implements ErrorCode {

    CONCURRENT_COMMAND(HttpStatus.CONFLICT, "동일 잔액에 대한 보정이 이미 진행 중입니다."),
    INVALID_STATE(HttpStatus.CONFLICT, "아직 DB에 반영되지 않은 응모·적립 메시지가 있어 보정할 수 없습니다."),
    REDIS_BALANCE_QUERY_FAILED(HttpStatus.SERVICE_UNAVAILABLE, "Redis 응모권 잔액을 조회할 수 없습니다.");

    private final HttpStatus status;
    private final String message;

    /** 외부 응답에서 사용할 오류 코드 이름을 반환한다. */
    @Override
    public String code() {
        return name();
    }

    /** 오류에 대응하는 HTTP 상태를 반환한다. */
    @Override
    public HttpStatus status() {
        return status;
    }

    /** 운영자에게 표시할 기본 오류 메시지를 반환한다. */
    @Override
    public String message() {
        return message;
    }
}
