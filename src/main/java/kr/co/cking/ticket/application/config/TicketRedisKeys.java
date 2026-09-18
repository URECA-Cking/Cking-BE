package kr.co.cking.ticket.application.config;

// ticket-earn.lua(EARN)와 향후 ticket 도메인 Lua가 참조하는 Redis 키 포맷.
public final class TicketRedisKeys {

    private TicketRedisKeys() {
    }

    public static String balance(Long creatorId, Long userId) {
        return "ticket:balance:" + creatorId + ":" + userId;
    }

    // SPEND의 idem:{requestId}와 분리된 미션 적립 replay 키다.
    public static String idemMission(String requestId) {
        return "idem:mission:" + requestId;
    }

    // FR-P2-006 확정 포맷. periodKeyYyyyMmDd는 yyyyMMdd(대시 없음) - EarnCommand.periodKey()의
    // YYYY-MM-DD와는 다른 표기이니 호출측에서 변환해서 넘겨야 한다.
    public static String earnGuard(Long userId, String missionType, Long creatorId, String periodKeyYyyyMmDd) {
        return "mission:earn-guard:" + userId + ":" + missionType + ":" + creatorId + ":" + periodKeyYyyyMmDd;
    }
}
