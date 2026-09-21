package kr.co.cking.event.application.dto.enums;

// entry-spend.lua 반환 결과코드 계약 (FR-P2-036, 통합 API 명세 v2.5 §5.4 10종 +
// BALANCE_MAINTENANCE). SYSTEM_ERROR는 Lua가 직접 반환하지 않고, 스크립트 실행
// 자체가 예외를 던졌을 때 EntrySpendServiceImpl이 매핑한다.
// BALANCE_MAINTENANCE는 수동 보정 락이 걸린 신규 요청에 Lua가 직접 반환한다(issue #172, HTTP 503).
public enum EntrySpendResultCode {
    SUCCESS,
    DUPLICATE_REPLAY,
    EVENT_NOT_OPEN,
    EVENT_CLOSED,
    INSUFFICIENT_BALANCE,
    INVALID_TICKET_COUNT,
    IDEMPOTENCY_CONFLICT,
    GATE_NOT_LOADED,
    BALANCE_NOT_LOADED,
    BALANCE_MAINTENANCE,
    SYSTEM_ERROR
}
