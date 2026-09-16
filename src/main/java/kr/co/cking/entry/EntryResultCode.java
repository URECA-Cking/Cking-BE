package kr.co.cking.entry;

/**
 * Redis Lua 응모 처리 결과코드 10종(통합 API 명세 v2.5 §5.3, 최종·CREATOR_MISMATCH 없음).
 * 실제 값은 T2-03(Redis Lua 원자 처리)이 채운다.
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
