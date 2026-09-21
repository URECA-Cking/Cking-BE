package kr.co.cking.ticket.domain;

import org.springframework.http.HttpStatus;

import kr.co.cking.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public enum TicketErrorCode implements ErrorCode {

    CONCURRENT_COMMAND(HttpStatus.CONFLICT, "동일 잔액에 대한 보정이 이미 진행 중입니다."),
    INVALID_STATE(HttpStatus.CONFLICT, "아직 DB에 반영되지 않은 응모·적립 메시지가 있어 보정할 수 없습니다.");

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
