package kr.co.cking.event.application.config;

import kr.co.cking.ticket.application.config.TicketRedisKeys;

// 응모 Lua(entry-spend.lua)가 참조하는 Redis 키 포맷.
// 데이터 구조.md §2 확정 스키마를 그대로 따른다.
public final class EntryRedisKeys {

    private EntryRedisKeys() {
    }

    public static String status(Long eventId) {
        return "event:status:" + eventId;
    }

    public static String endAt(Long eventId) {
        return "event:endat:" + eventId;
    }

    // ticket:balance 포맷은 TicketRedisKeys가 단일하게 관리한다.
    public static String balance(Long creatorId, Long userId) {
        return TicketRedisKeys.balance(creatorId, userId);
    }

    // ticket:maint 포맷도 TicketRedisKeys가 단일하게 관리한다(issue #172).
    public static String maintenance(Long creatorId, Long userId) {
        return TicketRedisKeys.maintenance(creatorId, userId);
    }

    public static String idem(String requestId) {
        return "idem:" + requestId;
    }

    // 차감 전에 fingerprint를 기록해, idem 저장 실패 시에도 재차감을 막는다.
    public static String spendGuard(String requestId) {
        return "entry:spend-guard:" + requestId;
    }

    public static String cutoff(Long eventId) {
        return "event:cutoff:" + eventId;
    }

    // 실시간 응모 현황(FR-P2-045~050) 집계 키. Gate 최초 적재와 같은 원자 단위에서
    // event-gate-load.lua가 DB 집계값으로 초기화하고, entry-spend.lua가 신규 SUCCESS
    // 경로에서만 증가시킨다. 존재 여부 자체가 "집계 적재됨"의 판단 기준이다.
    public static String entryTotal(Long eventId) {
        return "event:entry-total:" + eventId;
    }

    public static String entrants(Long eventId) {
        return "event:entrants:" + eventId;
    }
}
