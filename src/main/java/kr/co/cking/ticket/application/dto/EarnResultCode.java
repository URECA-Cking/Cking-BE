package kr.co.cking.ticket.application.dto;

import kr.co.cking.ticket.application.TicketEarnService;

/**
 * {@link TicketEarnService#earn} 결과코드. 통합 API 명세 v2.5 §3.5 기준 6종 + BALANCE_MAINTENANCE.
 * 이 자체는 외부 HTTP 상태를 갖지 않는다 — 호출 측(T1 미션완료 API)이 §4.2
 * 매핑표(EARN_ACCEPTED→202, ALREADY_PROCESSED→200, DUPLICATE_MISSION→409,
 * REQUEST_ID_CONFLICT→409, EARN_PROCESSING_FAILED→503, EARN_STATUS_UNKNOWN→504,
 * BALANCE_MAINTENANCE→503)대로 변환한다. BALANCE_MAINTENANCE는 issue #172 추가 -
 * 수동 보정 락이 걸린 신규 요청에 Lua가 직접 반환한다.
 */
public enum EarnResultCode {
    EARN_ACCEPTED,
    ALREADY_PROCESSED,
    DUPLICATE_MISSION,
    REQUEST_ID_CONFLICT,
    EARN_PROCESSING_FAILED,
    EARN_STATUS_UNKNOWN,
    BALANCE_MAINTENANCE
}
