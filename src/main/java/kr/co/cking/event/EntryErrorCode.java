package kr.co.cking.event;

import kr.co.cking.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/**
 * {@link EntryResultCode}의 실패 케이스를 HTTP로 매핑한다. SUCCESS/DUPLICATE_REPLAY는
 * 실패가 아니므로 제외.
 *
 * <p>통합 API 명세 v2.5에는 명령 API별 HTTP Status 상세가 아직 P1(미확정)이라,
 * §8 공통 오류코드 표(INVALID_STATE/IDEMPOTENCY_CONFLICT→409, SYSTEM_ERROR→500)에
 * 맞춰 잠정 매핑한 스텁이다. 실제 Lua 연동(T2-03) 확정 시 갱신한다.
 */
@RequiredArgsConstructor
public enum EntryErrorCode implements ErrorCode {

    EVENT_NOT_OPEN(HttpStatus.CONFLICT, "이벤트가 응모 가능한 상태가 아닙니다."),
    EVENT_CLOSED(HttpStatus.CONFLICT, "이벤트 응모가 마감되었습니다."),
    INSUFFICIENT_BALANCE(HttpStatus.CONFLICT, "응모권 잔액이 부족합니다."),
    INVALID_TICKET_COUNT(HttpStatus.BAD_REQUEST, "응모 수량이 올바르지 않습니다."),
    IDEMPOTENCY_CONFLICT(HttpStatus.CONFLICT, "동일 requestId에 다른 요청 내용이 전달되었습니다."),
    GATE_NOT_LOADED(HttpStatus.SERVICE_UNAVAILABLE, "응모 처리 준비 중입니다. 잠시 후 다시 시도해주세요."),
    BALANCE_NOT_LOADED(HttpStatus.SERVICE_UNAVAILABLE, "잔액 정보 준비 중입니다. 잠시 후 다시 시도해주세요."),
    SYSTEM_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 오류가 발생했습니다.");

    private final HttpStatus status;
    private final String message;

    public static EntryErrorCode from(EntryResultCode code) {
        return switch (code) {
            case EVENT_NOT_OPEN -> EVENT_NOT_OPEN;
            case EVENT_CLOSED -> EVENT_CLOSED;
            case INSUFFICIENT_BALANCE -> INSUFFICIENT_BALANCE;
            case INVALID_TICKET_COUNT -> INVALID_TICKET_COUNT;
            case IDEMPOTENCY_CONFLICT -> IDEMPOTENCY_CONFLICT;
            case GATE_NOT_LOADED -> GATE_NOT_LOADED;
            case BALANCE_NOT_LOADED -> BALANCE_NOT_LOADED;
            case SYSTEM_ERROR -> SYSTEM_ERROR;
            case SUCCESS, DUPLICATE_REPLAY ->
                    throw new IllegalArgumentException(code + "는 실패 코드가 아니라 에러로 변환할 수 없습니다.");
        };
    }

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
