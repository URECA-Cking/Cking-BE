package kr.co.cking.ticket.application.config;

// common-ticket-earn.lua가 참조하는, 크리에이터 축이 없는 공용 응모권 Redis 키 포맷.
public final class CommonTicketRedisKeys {

    private CommonTicketRedisKeys() {
    }

    public static String balance(Long userId) {
        return "ticket:balance:common:" + userId;
    }

    public static String idemMission(String requestId) {
        return "idem:common-mission:" + requestId;
    }

    // periodKeyYyyyMmDd는 yyyyMMdd(대시 없음) — 호출측에서 변환해서 넘겨야 한다.
    public static String earnGuard(Long userId, String missionType, String periodKeyYyyyMmDd) {
        return "mission:earn-guard:common:" + userId + ":" + missionType + ":" + periodKeyYyyyMmDd;
    }

    // 공용 보정 락(이슈 #243). 공용 잔액 수동 보정 자체는 아직 없어 entry-spend.lua의
    // EXISTS 확인 대상으로만 쓰인다 — 후속 공용 보정 작업이 이 포맷을 그대로 재사용한다.
    public static String maintenance(Long userId) {
        return "ticket:maint:common:" + userId;
    }
}
