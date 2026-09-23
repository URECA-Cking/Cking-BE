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
}
