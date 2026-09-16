package kr.co.cking.event.domain;

/**
 * Redis Lua 응모 처리 결과코드 10종(통합 API 명세 v2.5 §5.3, 최종·CREATOR_MISMATCH 없음).
 * {@link kr.co.cking.event.application.dto.enums.EntrySpendResultCode}와 이름이 동일하게 유지되어야 한다.
 */
public enum EntryResultCode {
    SUCCESS,
    DUPLICATE_REPLAY,
    EVENT_NOT_OPEN,
    EVENT_CLOSED,
    INSUFFICIENT_BALANCE,
    INVALID_TICKET_COUNT,
    IDEMPOTENCY_CONFLICT,
    GATE_NOT_LOADED,
    BALANCE_NOT_LOADED,
    SYSTEM_ERROR
}
